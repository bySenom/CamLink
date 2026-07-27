# Health and stream protection

CamLink makes all protection decisions in the Android client. The Hub displays
and edits the versioned configuration, but disconnecting it does not disable
Android-side protection. A disconnect stops Camera2, releases encoder/surfaces,
stops the foreground service and unregisters health listeners.

## Public measurements only

| Signal | API | Meaning |
| --- | --- | --- |
| Battery level, power source | `Intent.ACTION_BATTERY_CHANGED`, `BatteryManager` | Percentage, charging and USB/AC/wireless source. |
| Battery temperature | `BatteryManager.EXTRA_TEMPERATURE` | Battery value in tenths of a degree Celsius; not a whole-device temperature. |
| Android thermal status | `PowerManager.getCurrentThermalStatus()` and `OnThermalStatusChangedListener` (API 29+) | Public Android severity from Normal to Shutdown. |
| Thermal Headroom | `PowerManager.getThermalHeadroom(0)` (API 30+) | Optional Android-provided value only when available. |
| FPS/drop count | MediaCodec presentation timestamps | Actual output FPS, total drops and a one-second recent-drop window. |

CamLink uses no Samsung-private APIs, reflection or kernel sensors. When battery
temperature is unavailable, the UI says `Temperatur: –`; it never presents an
invented whole-phone temperature.

## Profiles and defaults

Battery-temperature values are **device-dependent user thresholds**, not
universal safety limits. Android and the battery BMS remain the hardware
protection authorities.

| Profile | Default behavior |
| --- | --- |
| Quality Lock | Keeps resolution/FPS, informs under load, stops at critical state and releases resources for emergency/shutdown. |
| Balanced | After 10 seconds under moderate/severe load: bitrate, then FPS, then resolution; at least 60 seconds between actions. |
| Maximum Safety | Earlier action: 25%/12% battery, 40/45 °C battery thresholds, 3-second hold, 30-second action interval. |
| Custom | Persists the exact user-selected values. |

The normal battery defaults are 20% warning, 8% critical, 42/47 °C warning/
critical. Review temperature values for the individual phone, case, charger and
ambient conditions. Low battery is ignored while charging by default; a
critical battery action remains configurable.

All fields are editable in **Protection settings** in Android. The Hub's
**Edit protection…** view displays the full JSON configuration, validates it
before sending, and waits for Android's confirmation. Android validates again
before persisting it.

## Safe throttling

CamLink first requests a dynamic bitrate change with
`MediaCodec.PARAMETER_KEY_VIDEO_BITRATE`. If unsupported, it requests an FPS
fallback. FPS/resolution changes notify the Hub, release the existing Camera2
session and encoder, start only a discovered non-`unsupported` profile, then
announce the active profile. A failed fallback attempts the prior stable
profile. Hold times, cooldowns and a profile-change budget prevent oscillation.
Historic drops remain visible but only fresh one-second drops trigger policy.

## Samsung Galaxy S22 test checklist

1. Install the debug APK and start the updated Windows Hub.
2. Keep the S22 powered and cool. Choose `Balanced` in **Protection settings**.
3. Stream a validated profile and confirm Android and Hub show FPS, `Akku: … °C`
   when available, and `Thermik: Normal`.
4. Change a harmless low-battery warning, save it and verify Hub confirmation.
5. Inspect public signals with:

   ```powershell
   $adb = 'C:\Users\SASHA\AppData\Local\Android\Sdk\platform-tools\adb.exe'
   & $adb logcat -v time -s CamLinkHealth:I CamLinkProtection:I CamLinkCamera:I '*:S'
   ```

Do not force `EMERGENCY` or `SHUTDOWN` by overheating or charging abuse. Those
states are handled when Android reports them.
