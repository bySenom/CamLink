# Camera2 profile validation

CamLink keeps Camera2 as its only live capture pipeline. `CamcorderProfile` is
used only as a vendor hint; normal Camera2 candidates are enumerated separately
from the stream map and the selected MediaCodec encoder.

## Candidate status

| Status | Meaning |
| --- | --- |
| `reported` | Camera2 metadata and a concrete hardware encoder report the combination. It has not yet completed CamLink's local capture probe. |
| `verified` | A local Camera2 session with the same preview-plus-encoder outputs as live mode ran for three seconds and delivered capture results plus encoded video near the requested FPS. |
| `unstable` | The session opened but generated too few frames or fell below 80% of requested FPS. |
| `unsupported` | Camera2, the encoder, or session setup rejected the candidate. |

Results are cached per Android build fingerprint, camera ID, resolution, FPS,
codec, and high-speed flag. Press **Validate camera profiles** again to run a
new full scan and overwrite cached results.

## S22 test procedure

1. Disconnect CamLink and close Samsung Camera, OBS DroidCam, or any other app
   holding a camera.
2. Install the current APK and open CamLink.
3. On the connection screen, grant camera permission and press
   **Validate camera profiles**. This runs one profile at a time and does not
   connect to the Hub or send video to OBS.
4. Keep the phone awake, cool, and connected to power. The check can take a
   while because each profile receives a three-second capture window.
5. Reconnect CamLink after the summary appears. Profiles will then be labelled
   `verified`, `unstable`, or `unsupported`.

The current code does not claim that 1080p60, 4K, or 8K are usable until this
test has succeeded on the real S22.

## Relevant ADB commands

```powershell
$env:ADB_VENDOR_KEYS = 'C:\Users\SASHA\.android\adbkey'
$adb = 'C:\Users\SASHA\AppData\Local\Android\Sdk\platform-tools\adb.exe'

& $adb logcat -c
& $adb logcat -v time -s CamLinkCapabilities:I CamLinkValidation:I CamLinkCamera:I '*:S'
```

`CamLinkCapabilities` logs hardware level, logical/physical IDs, sensor size,
focal lengths, FPS ranges, output sizes, high-speed ranges, and candidate
profiles. `CamLinkValidation` logs requested and measured FPS, frame counts,
dropped-frame estimate, encoder name/profile/level, actual resolution,
bitrate, exposure time, thermal status, and any rejection reason.

For a focused view of the active request after selecting a profile:

```powershell
& $adb shell "dumpsys media.camera | grep -A 1 -e 'android.control.aeTargetFpsRange' -e 'android.sensor.exposureTime' -e 'android.control.afMode' -e 'android.control.awbMode'"
```
