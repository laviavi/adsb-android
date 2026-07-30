# ADS-B Android � Project Status

**Last updated:** 2026-07-23
**Location:** D:\sandbox\adsb-android
**Python reference project:** github.com/2139avi/adsb (private repo)

## Toolchain (installed on this machine)
- JDK 17 (Temurin): C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
- Android SDK: C:\Android\Sdk (platform-tools, build-tools 35.0.0, platform android-35, emulator + AVD "adsb_test")
- Gradle 8.10.2: C:\Gradle\gradle-8.10.2
- Android Studio installed at C:\Program Files\Android\Android Studio (JBR incomplete, not used for builds)
- Dev phone: Pixel 6 (oriole), USB debugging on, stay-awake-while-plugged-in enabled
- Package: com.laviavi.adsbandroid.debug

## Current live setup
- dump1090.exe (D:\SDR\Dump1090-main) running on PC, real RTL-SDR antenna connected
- Network ports: 8090 (HTTP/JSON), 30001 (raw in), 30002 (raw out/AVR text), 30003 (SBS)
- PC WiFi IP: 192.168.0.249 (NordVPN present but not routing LAN traffic)
- Phone connects via NETWORK source, AVR_TEXT format, 192.168.0.249:30002
- App default AppConfig points at this setup out of the box

## Build & deploy (no Android Studio needed)
```
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "C:\Android\Sdk"
cd D:\sandbox\adsb-android
.\gradlew.bat :app:assembleDebug --no-daemon
C:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
C:\Android\Sdk\platform-tools\adb.exe shell am start -n com.laviavi.adsbandroid.debug/com.laviavi.adsbandroid.ui.MainActivity
```
Also: `build-and-install.bat` at project root does all three steps.

## Debugging without watching the phone
`ErrorLog` object (pipeline/ErrorLog.kt) logs pipeline lifecycle/errors.
Query anytime: `adb logcat -s AdsbErrorLog:*`

## When the phone is physically with the user
Remote inspection/interaction (screenshots, simulated taps, logcat polling, retry loops on
physical/transport operations like WiFi ADB installs) is a fallback, not the default. After one
failed or one confirmatory remote attempt, ask the user to look at the screen / tap something /
report the result directly instead of continuing to loop remotely. This is especially true for
install/deploy friction (flaky WiFi ADB, USB transport issues) — that's infra noise, not app
behavior, and a human standing at the device resolves it in seconds (plug in a cable, tap
install) versus many retried tool calls.
