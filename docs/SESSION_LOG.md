# Session Log — chronological steps taken

1. Compared FA Android decoder (flightaware/adsb-flight-scanner-android) vs Python project vs Android plan.
   FA has better preamble detection (phase-correlation) and magnitude LUT; Python has richer enrichment/DF coverage.
2. Built Phase 0 skeleton: Gradle multi-module (app + core-test), Hilt DI, Room schema, Compose UI skeleton,
   PipelineService foreground service, DummySource, CLAUDE.md coding standards doc.
3. Installed full local Android build toolchain (JDK 17, Android SDK, Gradle standalone, emulator+AVD) so
   Claude can compile/test autonomously without Android Studio.
4. Verified Phase 0 on-machine: 25 JVM tests, caught 1 bad CRC test vector, fixed.
5. Built Phase 1: full MessageDecoder (all DF/TC), CPR global decode, Demodulator with FA-style LUT +
   simplified phase preamble detection, NetworkSource (AVR parse), FileSource, AircraftManager merge logic.
   52 tests, caught NL-table boundary off-by-one and 2 bad CPR test vectors, fixed.
6. Built Phase 3 (USB OTG): researched rtl_tcp_andro — original repo dead, found signalware/rtl_tcp_andro- fork
   (Intent-based iqsrc:// protocol, no native binary needed). Built UsbRtlSdrSource, SdrSourceActivity trampoline,
   UsbHotplugReceiver, SdrExceptions, updated PipelineService/SettingsScreen/AndroidManifest. 16 new tests, 99 total.
7. Set up full build-and-install pipeline: gradlew wrapper, libs.versions.toml, settings.gradle.kts,
   fixed AndroidX/theme/AppCompat build errors. Installed on Samsung S24 initially.
8. Fixed SdrSourceActivity crash: Theme.NoDisplay requires finish() before onResume() completes; our async
   Intent launch violated this. Changed to Theme.Translucent.NoTitleBar.
9. Fixed driver-detection false negative: missing <queries> manifest block (required Android 11+) caused
   isDriverInstalled() to return false even though marto.rtl_tcp_andro was installed. Added <queries> package entry.
10. Switched primary dev device to Pixel 6 (oriole) per user instruction — will use this device exclusively going forward.
11. Built PC-to-phone bridge for dev/test without OTG hardware: verified dump1090.exe running on PC with real
    antenna, real aircraft decoding confirmed via localhost:8090/data/aircraft.json. Pointed AppConfig defaults
    at PC's dump1090 AVR output (192.168.0.249:30002, NetworkFormat.AVR_TEXT). Updated observer lat/lon to
    match dump1090.cfg homepos (33.9524737,-117.3317861).
12. Diagnosed and fixed silent pipeline death bug (see PHASE_PROGRESS.md "Known bug fixed"). Added ErrorLog.kt
    for remote debugging via adb logcat -s AdsbErrorLog:* since user cannot watch phone screen continuously.
13. User requested move to Claude Code — this file, PROJECT_STATUS.md, and PHASE_PROGRESS.md written to
    D:\sandbox\adsb-android\docs\ as handoff memory.

# User preferences observed this session
- Wants minimal narration, direct action over explanation
- Prefers Desktop Commander / Windows-MCP tools used proactively without asking permission
- Corrects immediately when told something is wrong — do not defend, just fix
- Values token efficiency highly — batch diagnostics, avoid redundant checks
- Working setup: Windows PC with RTL-SDR Blog V4 antenna + dump1090, Pixel 6 dev phone, no VPN currently active
