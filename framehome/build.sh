#!/usr/bin/env bash
# Builds framehome.apk without Gradle, using the Android SDK tools directly.
# Usage: ./build.sh            -> build only
#        ./build.sh install    -> build, install over ADB, set as home, grant permissions
#        ./build.sh remote     -> build and update the frame over Wi-Fi via its web remote
#
# Settings come from frame.env next to this script (copy frame.env.example) or the environment.
set -euo pipefail
cd "$(dirname "$0")"
if [ -f frame.env ]; then set -a; . ./frame.env; set +a; fi

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
ADB="$SDK/platform-tools/adb"
# javac: use JAVA_HOME if it has one, else a system javac.
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
PKG=com.jay.framehome

rm -rf build && mkdir -p build/gen build/classes build/dex

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$PLATFORM" --manifest AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 29 \
    --java build/gen -o build/unsigned.apk build/res.zip

"$JAVAC" --release 11 -nowarn -Xlint:-options -classpath "$PLATFORM" -d build/classes \
    $(find build/gen src -name '*.java')

"$BT/d8" --release --min-api 29 --lib "$PLATFORM" --output build/dex \
    $(find build/classes -name '*.class')

(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

# Your own signing key, created on first build. Keep it private and keep a backup: the frame
# only accepts remote updates signed with the same key.
KEYSTORE="${KEYSTORE:-keystore.jks}"
KS_PASS="${KEYSTORE_PASSWORD:-framehome}"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias framehome -keyalg RSA -keysize 2048 -validity 36500 \
        -dname "CN=Frame Home" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out framehome.apk build/aligned.apk
echo "Built $(pwd)/framehome.apk"

if [ "${1:-}" = "remote" ]; then
    # Update over Wi-Fi through the frame's web remote (no ADB needed; requires device owner).
    : "${FRAME_IP:?set FRAME_IP in frame.env}"
    FRAME_URL="${FRAME_URL:-http://$FRAME_IP:8080}"
    PIN="${FRAME_PIN:?set FRAME_PIN in frame.env (shown on the frame home screen)}"
    curl -fsS -H "X-Pin: $PIN" -H "Content-Type: application/vnd.android.package-archive" \
        --data-binary @framehome.apk "$FRAME_URL/api/update"; echo
    exit 0
fi

if [ "${1:-}" = "install" ]; then
    # Use USB if exactly one device is attached; otherwise connect over Wi-Fi (wireless ADB is
    # enabled from USB with `adb tcpip 5555` and lasts until the frame restarts).
    FRAME_ADDR="${FRAME_ADDR:-${FRAME_IP:+$FRAME_IP:5555}}"
    if [ -z "${ANDROID_SERIAL:-}" ] && [ -n "$FRAME_ADDR" ] && ! "$ADB" get-state >/dev/null 2>&1; then
        "$ADB" connect "$FRAME_ADDR" >/dev/null 2>&1 || true
        export ANDROID_SERIAL="$FRAME_ADDR"
    fi
    "$ADB" install -r framehome.apk
    "$ADB" shell dpm set-active-admin "$PKG/.AdminReceiver" || true
    "$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
    "$ADB" shell appops set "$PKG" WRITE_SETTINGS allow
    "$ADB" shell cmd package set-home-activity "$PKG/.HomeActivity"
    # Enable the home-guard accessibility service, keeping any already-enabled services.
    SVC="$PKG/$PKG.HomeGuardService"
    CUR="$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')"
    case "$CUR" in
        *"$SVC"*) ;;
        ""|null) "$ADB" shell settings put secure enabled_accessibility_services "$SVC" ;;
        *) "$ADB" shell settings put secure enabled_accessibility_services "$CUR:$SVC" ;;
    esac
    "$ADB" shell settings put secure accessibility_enabled 1
    # Frame Home's slideshow is also the screensaver: an idle screen goes back to photos.
    "$ADB" shell settings put secure screensaver_enabled 1
    "$ADB" shell settings put secure screensaver_components "$PKG/$PKG.FrameDream"
    "$ADB" shell settings put secure screensaver_activate_on_sleep 1
    # Immich connection: pushed as the app's config file (the album can be changed later from
    # the phone remote).
    if [ -n "${IMMICH_URL:-}" ] && [ -n "${IMMICH_API_KEY:-}" ]; then
        ALBUM_ID="${IMMICH_ALBUM_ID:-}"
        CFG="$(mktemp)"
        printf '{"url":"%s","apiKey":"%s","albumId":"%s","intervalSeconds":%s}\n' \
            "${IMMICH_URL%/}" "$IMMICH_API_KEY" "$ALBUM_ID" "${SLIDE_SECONDS:-30}" > "$CFG"
        DEST="/sdcard/Android/data/$PKG/files"
        "$ADB" shell mkdir -p "$DEST"
        "$ADB" push "$CFG" "$DEST/immich.json" >/dev/null
        rm -f "$CFG"
        echo "Pushed Immich config (album $ALBUM_ID)."
    fi
    "$ADB" shell am start -n "$PKG/.HomeActivity"
    echo "Installed and set as home."
fi
