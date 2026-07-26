# CamLink

CamLink makes an Android phone a remotely controlled, low-latency camera for OBS on Windows. The first supported target is the Samsung Galaxy S22 family, but the camera app does **not** hard-code Samsung profiles: it discovers every camera, capture size, high-speed mode, zoom range and supported camera control at runtime.

## What is implemented

- Native Android Camera2 capture, including front/rear selection, ultra-wide/wide/tele discovery, zoom, focus mode, exposure compensation, device-supported auto-white-balance presets and torch. Camera2 candidates are merged with (rather than hidden by) CamcorderProfile hints, then validated against a concrete hardware encoder.
- H.264 hardware encoding and HEVC/H.265 fallback for camera sizes that the phone cannot encode as H.264 (normally relevant to 8K), over a private local TCP link.
- Windows companion with remote controls and an OBS-backed Windows virtual camera. Select **OBS Virtual Camera** as a Video Capture Device in OBS, Discord, Teams, Zoom, and similar applications.
- USB setup helper (`scripts/camlink-usb.ps1`) using `adb reverse`; Wi-Fi is supported directly. With both devices on the same trusted Wi-Fi, the app finds the hub automatically—no PC IP is required. Smart mode tries USB first, then performs the Wi-Fi search.
- GitHub Releases update checks for both the Hub and phone app. The Hub can download, verify and restart into an update; Android downloads the APK and opens the required system installation confirmation.
- Bluetooth is deliberately excluded as a video transport: its bandwidth and latency are insufficient for HD, let alone 4K/8K. This first build leaves Bluetooth pairing/control as the next optional extension; video uses USB or Wi-Fi.

## Important capability rule

The S22 camera application, encoder and thermal state decide which combinations can actually be opened. CamLink queries Android's `StreamConfigurationMap`, advertised high-speed modes and normal FPS ranges, then exposes only candidates returned by the phone. The camera start is the final validation because an encoder surface can impose a tighter limit than a raw camera output.

In particular, do not assume that every S22 variant offers 8K/30 or 1080p/120 through third-party Camera2 capture. Samsung firmware can expose its own Camera app modes without exposing the same mode to third-party apps. When Camera2 rejects a requested profile, CamLink keeps the error visible and offers the next verified profile; it never silently records at a different setting.

Use **Validate camera profiles** in the Android connection screen to test each candidate locally and cache it as verified, unstable, or unsupported. See [camera-validation.md](docs/camera-validation.md) for the test procedure and Logcat filters.

## Project layout

```text
android/              Android camera application (Kotlin / Camera2)
desktop/              Windows companion (C# / Windows Forms / RTSP server)
scripts/              USB bridge and setup helpers
docs/                 Protocol and operating notes
```

## Prerequisites

- Windows 11 and .NET SDK 10 (the companion targets `net10.0-windows`).
- Android Studio + Android SDK Platform 35, build-tools and platform-tools (`adb`).
- A USB data cable for USB mode, or the phone and PC on the same trusted Wi-Fi/LAN for Wi-Fi mode.
- OBS Studio with its standard FFmpeg media source.

## Quick start

1. Install Android Studio and SDK Platform 35. Create `android/local.properties` with `sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.
2. Build the phone app with [scripts/build-android.ps1](scripts/build-android.ps1), then install it with [scripts/install-s22-debug.ps1](scripts/install-s22-debug.ps1).
3. Start the Windows companion with [scripts/start-desktop.ps1](scripts/start-desktop.ps1).
4. **USB:** enable Developer options and USB debugging on the phone, connect it, then run `powershell -ExecutionPolicy Bypass -File scripts/camlink-usb.ps1`. **Wi-Fi:** connect both devices to the same trusted Wi-Fi, start the Hub, and choose **Find hub on Wi-Fi** in the phone app (or leave the address blank in **Smart**). A manual PC IPv4 address remains available as a fallback.
5. In the phone app, select **Smart** or **USB**, then press **Connect**. After capability discovery, CamLink enters the full-screen camera view, locks the current rotation, and starts a stream-safe back-camera profile automatically. Camera, profile, zoom, focus, white balance, exposure, and light remain available as overlays on the phone and in the companion.
6. Run [scripts/configure-obs-virtual-camera.ps1](scripts/configure-obs-virtual-camera.ps1) once, then start OBS with the generated CamLink profile (the companion’s **Start Windows camera** button does this when no other OBS instance is open). Select **OBS Virtual Camera** in the target application. The local RTSP endpoint remains an internal bridge for the dedicated OBS collection.

## Security model

The current development build deliberately binds the Windows hub to the local network so Wi-Fi can work. Use only on a trusted LAN. Before distributing beyond a private network, add the planned QR pairing token and TLS layer described in [docs/protocol.md](docs/protocol.md). USB mode is local by design.

## Updates

Release assets and the publishing process are documented in [docs/updates.md](docs/updates.md). Both clients verify a SHA-256 checksum before applying a downloaded package.
