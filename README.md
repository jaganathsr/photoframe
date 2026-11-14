# Frame Home

A replacement home screen and photo slideshow for old Android photo frames, built first for the **Lenovo Smart Frame (CD-3L501F)**, whose official software and Google Photos sync are no longer supported.

> Work in progress.

## Is this my frame?

Frame Home was built and tested on the **Lenovo Smart Frame**, model **CD-3L501F** ([Lenovo product page](https://www.lenovo.com/us/en/p/smart-devices/smart-home/smart-home-series/lenovo-cd-3l501/zziszsdcd04)):

- a 21.5-inch Full HD wall/desk photo frame with a matte, anti-glare screen and **no touchscreen**
- sold around 2020–2021 as a Google Photos frame, set up with the *Lenovo Smart Frame* phone app
- the model number is on the label on the back; in Android settings the model shows as **Lenovo CD-3L501F**

## Why this exists

The Lenovo Smart Frame was a good piece of hardware sold as a Google Photos frame. Then two things happened:

- **Lenovo stopped supporting it.** No more software updates, and its companion app and cloud service no longer work, so the frame can't be set up or synced the official way.
- **Google cut off third-party access to Google Photos.** Google's 2025 changes to the Photos API removed third-party apps' ability to read your library and shared albums. The official frame software and many third-party slideshow apps people switched to stopped syncing.

The result: a working 21.5" screen that can't show your photos. Third-party apps were also fragile in other ways. One popular replacement depended on the Amazon Appstore's license check, and stopped launching after Amazon shut down its Appstore for Android devices.

Frame Home takes the cloud dependency out. It replaces the frame's software with a home screen and slideshow that read photos from sources you control: your own Immich server, a network share, or a folder on the frame. Everything is configured from a page on your phone.

## Features

- Photo sources, chosen from your phone with a **Test & save** check:
  - an [Immich](https://immich.app) album
  - a network share over **SMB** (Windows PCs, Synology, QNAP, TrueNAS, Samba)
  - a folder on the frame or a USB stick
- Full-screen slideshow: crossfade, slide, zoom or Ken Burns transitions, shuffle with an optional bias towards recent photos, adjustable photo duration, clock overlay. Photos are cached on the frame, so it keeps playing if the source is offline.
- Sleep and wake schedule: screen off at night, back on in the morning.
- **Phone remote**: a page served by the frame (`http://FRAME_IP:8080`, PIN-protected) to change every setting, start or skip photos, adjust brightness, see what's showing, and send updates.
- On-frame **Setup checklist**: install from the browser, no computer needed.
- Keeps itself as the home screen even on frames whose vendor launcher can't be disabled.

## Install

**No computer needed.** Download the latest `FrameHome-<version>.apk` from the Releases page in the frame's browser, open it, and follow the Setup checklist that appears.

- **[Step-by-step guide for the Lenovo Smart Frame](docs/lenovo-smart-frame.md)**

Other Android 10+ frames and tablets should work the same way: you need a keyboard (or touchscreen) to get through Android's permission screens once.

## For developers

Build from source with the Android SDK (build-tools 35, platform 35), a JDK 11+ and `curl`. No Gradle needed.

```bash
cd framehome
cp frame.env.example frame.env   # your frame's IP, optional Immich defaults
./build.sh                       # build framehome.apk
./build.sh install               # install over ADB and grant all permissions
./build.sh remote                # update over Wi-Fi via the phone-remote API
./build.sh release               # versioned APK in dist/ for a GitHub release
```

The first build downloads the SMB library ([smbj](https://github.com/hierynomus/smbj)) and its dependencies from Maven Central into `framehome/libs/`, checked against pinned SHA-256 hashes.

APKs are signed with `framehome/keystore.jks`, which is created on first build. Keep it private and backed up: frames only accept updates signed with the same key, so releases must always be signed with the same keystore.

The ADB extras (silent updates as device owner, wireless ADB, removing vendor apps) are in the [guide's developer section](docs/lenovo-smart-frame.md#for-developers-optional-adb).

`immich-sync/` has an optional NAS script that mirrors a shared Google Photos album into Immich.

## Disclaimer

Frame Home is a free hobby project to keep your frame alive after Lenovo moved on, and you install it entirely at your own risk: if your frame turns into a very flat, very expensive paperweight or reboots forever, that's on you, not us. It's provided as is, with no warranty of any kind (see the [MIT License](LICENSE)).

## Need help?

We're happy to help when we can, with no promises on response time (we have frames to stare at). Open an issue on GitHub, or email:

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/contact-dark.png">
  <img src="docs/images/contact-light.png" alt="Contact email address, shown as an image to keep spam bots away" width="150" height="20">
</picture>

## License

[MIT](LICENSE) © 2026 Jaganath Achari
