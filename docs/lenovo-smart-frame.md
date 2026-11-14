# Setting up a Lenovo Smart Frame (CD-3L501F)

The Lenovo Smart Frame runs Android 10, but it has no touchscreen and hides most of the normal Android settings. You don't need a computer to install Frame Home: you download the app on the frame and turn on a few permissions, all with a keyboard. It takes about 20 minutes.

## What you need

- A **mouse and keyboard** you can plug into the frame's USB port. The easiest is a wireless keyboard + mouse set that shares one USB receiver, because the frame has only one USB port. (A USB hub with a wired mouse and keyboard also works.)
- The frame connected to your **home Wi-Fi**.
- A **phone** on the same Wi-Fi, for the remote control afterwards.

Basics on the frame: the **mouse** clicks things like on a computer; on the keyboard, **arrow keys** move around, **Enter** selects, **Esc** goes back.

## 1. Download and install Frame Home

1. Plug the mouse and keyboard receiver into the frame.
2. Open the built-in web browser. Lenovo's screen has no app list, so go through Settings:
   - Move the mouse pointer to the **very top edge** of the screen. Android's pull-down panel appears; click the **gear icon** to open **Settings**.
   - In Settings, click **search** (the magnifier) and type `browser`.
   - Choose **Browser** (it says *App info* underneath), then choose **Open**.
3. In the browser, go to the project's **Releases** page and download the latest `FrameHome-<version>.apk`.
4. Open the downloaded file. Android asks whether the browser may install apps: choose **Settings**, turn on **Allow from this source**, press **Esc**, then choose **Install**.
5. When it's installed, choose **Open**.

This is how other apps (such as Chrome or the Amazon Appstore) can be installed on the frame too. Once Frame Home is installed, its **Apps** tile lists every app, so you won't need the Settings trick again.

## 2. Follow the Setup checklist on the frame

Frame Home opens its **Setup** screen. Each item has a button that jumps to the right Android screen; switch the setting on, press **Esc** to come back, and the item turns green.

Required:

- **Turn the screen off at night**: choose **Activate**. Frame Home uses this only to switch the screen off at your sleep time.
- **Keep Frame Home on screen**: in Accessibility, open **Frame Home** and turn it on. The Lenovo frame app can't be removed, and this helper switches back to Frame Home whenever it pops up and makes the Home key work.
- **Allow "Display over other apps"**: lets the morning wake-up start the slideshow by itself.

Recommended:

- **Make Frame Home the home app**: choose **Frame Home** in the dialog.
- **Turn off "Stay awake"**: only shows as open if it's on. It's in Developer options; if it's on, the screen can never sleep.

Optional:

- **Brightness control**: lets the phone remote change brightness.
- **Photos from a folder or USB stick**: only if you want that photo source.
- **Updates from the phone remote**: lets you send new versions from your phone; the frame then asks you to confirm **Install**.
- **Use the slideshow as screensaver**: choose **Frame Home slideshow**.

You can reopen the checklist any time from the **Setup** tile on Frame Home's home screen.

## 3. Use the phone remote

The Setup screen and the home screen show an address like `http://192.168.1.50:8080` and a **PIN**.

1. On your phone, open that address in the browser and enter the PIN. Bookmark the page.
2. Under **Photo source**, choose where your photos come from (an Immich server, a network share, or a folder or USB stick on the frame) and press **Test & save**.

Everything else (photo duration, transitions, sleep and wake times, brightness) is on that page too.

Tips:

- On recent Android phones, Chrome and Brave need the **Nearby devices** permission (phone Settings → Apps → Chrome → Permissions) before they can open pages on your home network. Without it the page just times out.
- Reserve the frame's IP address in your router (often called *DHCP reservation*) so the address never changes.

## Troubleshooting

| Problem | Fix |
|---|---|
| Pressing Home briefly shows Lenovo's "Download the App" screen | Expected for a moment; Frame Home's helper switches back. If it stays, check **Keep Frame Home on screen** in Setup. |
| Screen never turns off at night | Turn off **Stay awake** (Setup shows it if it's on). |
| Screen stays dark in the morning | Check **Display over other apps** in Setup. |
| Phone remote times out | Phone on the same Wi-Fi (not a guest network), **Nearby devices** permission for the browser, no VPN exit node. |
| Slideshow closes when someone walks past | Update Frame Home; current versions ignore the frame's hand-gesture sensor except left/right waves, which change photo. |

## For developers (optional): ADB

None of this is needed to use Frame Home. It's for building from source, silent updates, and removing leftover Lenovo apps.

### Turn on USB debugging

Lenovo's settings menu has no "About" page. Open **Settings** (mouse pointer to the top edge, then the gear icon), use **search** to find **Build number** (or install *Activity Launcher* and open **Settings → Settings**), then press **Enter** on Build number 7 times. In **Settings → System → Developer options**, turn on **USB debugging**.

The frame has one USB port, so when the computer cable is plugged in, the keyboard isn't. Pair a **Bluetooth keyboard** (Settings → Connected devices → Pair new device) to accept the "Allow USB debugging?" prompt.

On Linux, allow access to the frame (USB vendor `0e8d`, MediaTek), then replug the cable:

```bash
echo 'SUBSYSTEM=="usb", ATTR{idVendor}=="0e8d", MODE="0666", GROUP="plugdev"' | sudo tee /etc/udev/rules.d/51-android.rules
sudo udevadm control --reload-rules
sudo udevadm trigger
```

### Build and install from source

```bash
cd framehome
cp frame.env.example frame.env   # set FRAME_IP
./build.sh install               # installs and grants every Setup permission for you
```

### Silent updates and remote restart (device owner)

Only possible over ADB, and only when no accounts are signed in on the frame (`adb shell dumpsys account` says `Accounts: 0`):

```bash
adb shell dpm set-device-owner com.jay.framehome/.AdminReceiver
./build.sh remote                # later updates over Wi-Fi, installed without a prompt
```

To uninstall Frame Home later, the device-owner status has to be removed first.

### Wireless ADB

```bash
adb tcpip 5555
adb connect 192.168.1.50:5555    # your frame's IP
```

This frame switches it off again whenever it restarts; repeat over USB.

### Remove leftover Lenovo apps

Removed for the frame's user only; restore any of them with `adb shell cmd package install-existing PACKAGE`:

```bash
for p in com.lmsa.app.lmsapad com.lenovo.mdm.admin.agent com.mesh.test.provisioner com.mediatek.autodialer com.mediatek.atmwifimeta; do
  adb shell pm uninstall -k --user 0 $p
done
```

Keep `com.walnut.bluetooth` (Lenovo's core frame app, which also runs Bluetooth) and `com.mediatek.sensorhub.ui` (sensors).

### Going back to Lenovo's software

```bash
adb shell cmd package set-home-activity com.walnut.bluetooth/com.smart_frame.SplashActivity
```

Then turn off Frame Home under Settings → Accessibility, or uninstall it.
