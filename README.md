# RPax TPMS — Kawasaki VN800 Classic

Android app (`com.rpax.tpms`) for the 5" M560-TPMS display (800x480), monitoring
two DJTPMS BLE tire sensors and driving a custom dashboard, plus an Android Auto
companion screen.

## Structure

```
app/src/main/java/com/rpax/tpms/
  TpmsDecoder.kt          BLE manufacturer-data decoder (pressure/temp/battery)
  TpmsSettings.kt         Threshold + preference persistence
  BleScannerService.kt    Foreground service: BLE scan, GPS speed, alerts (sound/vibe/Wear OS)
  CustomDashboardView.kt  Canvas-drawn 800x480 dashboard UI
  DashboardActivity.kt    Fullscreen host activity + permission handling
  RpaxCarAppService.kt    Android Auto CarAppService entry point
  RpaxScreen.kt           Android Auto PaneTemplate screen
  MainActivity.kt         Threshold configuration screen (launcher activity)

app/src/main/res/xml/automotive_app_desc.xml   Android Auto app descriptor
app/src/main/res/drawable/ic_tpms_icon.xml     Tire + warning icon
app/src/main/res/drawable/ic_motorcycle.xml    Cruiser frame vector graphic
app/src/main/res/values/styles.xml             App + fullscreen themes
app/src/main/AndroidManifest.xml               Permissions, services, activities
app/build.gradle.kts                           Module dependencies
```

## Before building

1. **Add a project-level `build.gradle.kts` and `settings.gradle.kts`** (standard
   Android Gradle Plugin + Kotlin plugin versions) — omitted here since they don't
   contain app logic.
2. **Add an alert sound file** at `app/src/main/res/raw/tpms_alert_beep.mp3` (or
   `.ogg`/`.wav`). `BleScannerService` references `R.raw.tpms_alert_beep`.
3. **Launcher icon**: supply `mipmap/ic_launcher` (or replace with an adaptive icon)
   referenced by the manifest.
4. **Wear OS companion**: this project only sends `MessageClient` messages from the
   phone side (`/rpax/tpms/alert`). A companion Wear OS app/tile would need to
   listen on that path via its own `WearableListenerService` to display it — pair
   this repo with a `wear` module if you want the watch UI itself.
5. **BLE MAC filtering**: `TpmsDecoder.FRONT_MAC` / `REAR_MAC` are hard-coded to the
   two sensor addresses given in the spec. Update them in `TpmsDecoder.kt` if you
   pair different physical sensors.

## Runtime permissions

The app requests, at first launch of `DashboardActivity`:
`ACCESS_FINE_LOCATION`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+), and
`POST_NOTIFICATIONS` (API 33+). BLE scanning and GPS speed will silently no-op
until granted.
