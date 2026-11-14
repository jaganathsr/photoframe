# Setting up a Lenovo Smart Frame (CD-3L501F)

The Lenovo Smart Frame runs Android 10, but it has **no touchscreen**, hides the normal Android settings, and its launcher can't be replaced the usual way. This guide gets Frame Home onto it. It takes about 30–45 minutes the first time.

## What you need

- A **keyboard** for the frame. A **Bluetooth keyboard** is best: the frame has a single USB port, and you need that port for the computer cable while still being able to press keys on the frame. A wired USB keyboard (or a keyboard + mouse combo dongle) works for the first steps.
- A **USB data cable** from the frame to your computer. Many cables are charge-only; if the computer never sees the frame, try another cable. Plugging in directly (not through a hub) is most reliable.
- A Linux or macOS computer with the Android SDK platform-tools (`adb`) and the build requirements in the [README](../README.md).
- The frame on your home Wi-Fi.

## 1. Get into the hidden Android settings

Lenovo's settings menu has no "About" page, so Developer options can't be turned on from it directly.

1. Plug the USB keyboard into the frame (or pair a Bluetooth keyboard from Lenovo's settings, if offered).
2. Open the frame's **Settings** and use **search** to find the full Android settings screens (search for **"Build number"** or **"About"**).
   - If search isn't available on your firmware, install a launcher helper such as *Activity Launcher* and open **Settings → Settings** from it.
3. Go to **About** and press **Build number** 7 times (select it and press Enter repeatedly) until you see *"You are now a developer!"*.

## 2. Developer options

In **Settings → System → Developer options**:

- Turn on **USB debugging**.
- Turn off **Stay awake** (otherwise the screen can never sleep at night).

While you're in the full settings, pair your **Bluetooth keyboard** (Settings → Connected devices → Pair new device) if you haven't already. You'll need it in the next step, when the USB port is taken by the computer cable.

## 3. Connect the frame to your computer

1. Unplug the USB keyboard and connect the frame to the computer with the data cable.
2. Linux only: allow your user to access the frame (its USB vendor ID is MediaTek `0e8d`), then unplug and replug the cable: ```bash echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="0e8d", MODE="0666", GROUP="plugdev"' \
     | sudo tee /etc/udev/rules.d/51-android.rules
sudo udevadm control --reload-rules
sudo udevadm trigger ```
3. Run `adb devices`. The frame shows **"Allow USB debugging?"**. Using the **Bluetooth keyboard**, tick **Always allow from this computer** and select **OK**.
4. `adb devices` should now list the frame as `device` (not `unauthorized` or `no permissions`).

## 4. Install Frame Home

```bash
cd framehome
cp frame.env.example frame.env   # set FRAME_IP (the frame's IP, from your router or Wi-Fi settings)
./build.sh install
```

`install` also sets Frame Home as the home app, grants its permissions, enables the small accessibility helper that keeps it in front of Lenovo's launcher (the Lenovo app can't be disabled without root), and makes the slideshow the screensaver.

The frame's home screen now shows the **phone remote address and PIN**.

## 5. Optional: remote updates and restart (device owner)

Making Frame Home the *device owner* lets you install updates and restart the frame from your phone or with `./build.sh remote`, without a cable. It only works when **no accounts** are signed in on the frame (`adb shell dumpsys account` should say `Accounts: 0`):

```bash
adb shell dpm set-device-owner com.jay.framehome/.AdminReceiver
```

To uninstall Frame Home later, the device-owner status has to be removed first.

## 6. Optional: wireless ADB

So you can unplug the cable and still use `adb` over Wi-Fi:

```bash
adb tcpip 5555
adb connect 192.168.1.50:5555   # your frame's IP
```

On this frame it switches off again whenever the frame restarts (Android 10 has no permanent wireless debugging without root); repeat the two commands over USB after a restart.

## 7. Optional: remove apps you no longer need

The frame ships with Lenovo apps that are useless once its cloud service is gone. These can be removed for the frame's user (restore any of them with `adb shell cmd package install-existing PACKAGE`):

```bash
for p in com.lmsa.app.lmsapad com.lenovo.mdm.admin.agent com.mesh.test.provisioner \
         com.mediatek.autodialer com.mediatek.atmwifimeta; do
  adb shell pm uninstall -k --user 0 $p
done
```

Keep `com.walnut.bluetooth`: it is Lenovo's core frame app and also runs Bluetooth (your keyboard). Also keep `com.mediatek.sensorhub.ui` (sensors, including the hand-gesture sensor).

## 8. Use the phone remote

Open `http://FRAME_IP:8080` (for example `http://192.168.1.50:8080`) on your phone, enter the PIN from the frame's home screen, and choose a photo source under **Photo source**. Tip: reserve the frame's IP in your router (DHCP reservation) so the address never changes.

On recent Android phones, Chrome and Brave need the **Nearby devices** permission (Settings → Apps → Chrome → Permissions) before they can open pages on your home network; without it the page just times out.

## Troubleshooting

| Problem | Fix |
|---|---|
| Computer doesn't see the frame at all | Use a data (not charge-only) cable, plug in directly, try another port. After a frame restart you may need to unplug and replug the cable. |
| `adb devices` says `no permissions` | Linux udev rule from step 3, then replug. |
| `adb devices` says `unauthorized` | Accept the "Allow USB debugging?" prompt on the frame (Bluetooth keyboard). |
| Pressing Home briefly shows Lenovo's "Download the App" screen | Expected for a moment; Frame Home's helper switches back automatically. |
| Screen never turns off at night | Turn off **Stay awake** in Developer options. |
| Phone remote times out | Phone on the same Wi-Fi (not a guest network), Nearby devices permission for the browser, no VPN exit node. |
| Slideshow exits when someone walks past | Update Frame Home; current versions ignore the frame's hand-gesture sensor except left/right waves, which change photo. |

## Going back to Lenovo's software

```bash
adb shell cmd package set-home-activity com.walnut.bluetooth/com.smart_frame.SplashActivity
adb shell settings put secure enabled_accessibility_services ""   # or remove only Frame Home's entry
```
