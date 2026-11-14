# Frame Home

A replacement home screen and photo slideshow for old Android photo frames, built first for the
**Lenovo Smart Frame (CD-3L501F)**, whose official software and Google Photos sync are no longer
supported.

> Work in progress. Today it shows photos from an [Immich](https://immich.app) server; local
> folders and LAN file shares are planned.

## Features

- Full-screen slideshow: crossfade / slide / zoom / Ken Burns transitions, shuffle with an optional
  bias towards recent photos, adjustable photo duration, clock overlay, offline cache.
- Sleep and wake schedule (screen off at night, back on in the morning).
- Phone remote: a web page served by the frame (`http://<frame-ip>:8080`, PIN-protected) to change
  every setting, start/stop the slideshow, skip photos, adjust brightness and see what's showing.
- Remote updates and restart from the phone (when installed as device owner).
- Keeps itself as the home screen even on frames whose vendor launcher can't be disabled.
- Optional NAS script that mirrors a shared Google Photos album into Immich (`immich-sync/`).

## Requirements

- An Android 10+ frame or tablet with **USB debugging** enabled (Developer options).
- A Linux/macOS computer with the Android SDK (build-tools 35, platform 35) and a JDK 11+.

## Quick start

```bash
cd framehome
cp frame.env.example frame.env      # fill in your frame IP and Immich details
./build.sh install                  # over USB (or Wi-Fi ADB)
```

Optional, for silent remote updates (only works on a frame with **no accounts** signed in):

```bash
adb shell dpm set-device-owner com.jay.framehome/.AdminReceiver
./build.sh remote                   # later updates over Wi-Fi, no cable
```

Then open `http://<frame-ip>:8080` on your phone and enter the PIN shown on the frame.

> On recent Android phones, Chrome/Brave need the **Nearby devices** permission to open pages on
> your home network.

## License

TBD
