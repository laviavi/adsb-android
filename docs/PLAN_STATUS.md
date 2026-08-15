# Plan vs. Code â€” Status and Path to Completion

Living document. Rewritten whenever work lands, so it always describes the code as
it is now â€” not a dated audit with corrections bolted on.

**Last updated:** 2026-08-05 â€” "Live" nav destination renamed to "Traffic";
History (formerly a Logs sub-tab) and Stats (formerly a Settings-pushed screen)
moved to live inside Traffic as peer sub-tabs alongside Live (default). Logs is
now Events-only (its History sub-tab is gone). Settings lost its "Aircraft
stats" entry point. StatsScreen's own Scaffold/TopAppBar/back-arrow were
stripped since it is now an embedded peer tab, not a pushed screen — Live and
History's content and behaviour are unchanged. The Live tab's "max range mi"
metric is now a running per-session max (survives an aircraft leaving range;
resets only on app start, Start-button, reconnect, or dongle replug — never on
a manual "Reset counters") instead of an instantaneous max over the currently
tracked list; its label is now "Max Range(miles)". The Live/History/Stats tab
row sits between Live's top bar and its filter chips, and the three sub-tabs
are swipeable via `HorizontalPager`, not just tap-to-switch. The dongle
reconnect race between the retry loop's own re-open and a hotplug-triggered
`restartPipeline()` is fixed (both now serialise through `sessionLock`), and
Traffic/Receiver's Start/Stop/Reconnect controls are unified (Receiver's
Start/Stop was hardcoded "STOP" and did nothing when idle; both screens now
confirm before Reconnect too, via shared `StopConfirmDialog`/
`ReconnectConfirmDialog`). Version **v1.6.6** (`versionCode` 21).

Coverage-history heatmap, best-range-ever, and first-time-seen milestone
notifications added (Receiver tab + PipelineService); a selectable OSM/Esri
base map (Settings â†’ Base map); the app version is now shown in Settings. Live
row split into a top strip (identity + data block) plus full-width
airline/route and type lines (5 lines total). Phase 1â€“4 UI models, shared
atoms, navigation, Live screen, AircraftRow, AircraftDetailSheet, LogsScreen,
ReceiverScreen (viewModel wrapper), SettingsScreen (viewModel wrapper),
MapScreen all compiling. PipelineService exposes public `startPipeline()`,
`stopPipeline()`, `reconnect()`. TextListScreen deleted (replaced by LiveScreen
+ 5-tab NavigationSuiteScaffold).
**Method:** read the source tree and tests; verified by reading files, not recall.
**Current:** 400 `:core:receiver` + 69 `:app` tests pass. Debug APK builds.

**Parity against the Python reference (`D:\SDR\adsb_v9_5`):**

| Harness | Coverage | Result |
|---|---|---|
| `GoldenFrameParityTests` | 26,760 frames (ICAO, callsign, altitude, squawk) | all match |
| `GoldenStateParityTests` | 3,484 aircraft rows over 267 checkpoints | all match |
| `EnrichmentTests` | 915,399 ICAOâ†’N-number addresses (SHA-256) | all match |

---

## 1. Where the work stands

**Correctness is in good shape.** Both parity harnesses are green against a
44-minute real capture. Every divergence from the reference is deliberate,
enumerated and asserted â€” none is absorbed by a tolerance.

**The remaining work is almost entirely UI.** There is no repository layer and no
Live / Receiver / Map / Detail / Logs screens as specified in the plan. The app is
`TextListScreen` with a History tab, which works but is not the operator console
the plan describes.

**The module rename is done, the model split is not.** The shared module is now
`:core:receiver` (was `:core-test`) â€” see Â§7. `:core:model` (a separate module for
`AircraftState`/`DecodedMessage`/`RawFrame`/enums, zero Android deps) was **not**
extracted alongside it: nothing consumes it yet â€” `:core:ui` and `:data` are
Phase 4/5 modules that don't exist â€” so splitting now would be modularising for a
second consumer that isn't there. Revisit when one of those modules is built.

---

## 2. Phase-by-phase status

### Phase 0 â€” Baseline and behaviour capture Â· **DONE**

| Item | Status |
|---|---|
| `tools/phase0_goldens.py`, byte-deterministic | âœ… |
| Frame goldens (26,512 + 235 + 13) | âœ… diffed by `GoldenFrameParityTests` |
| State goldens (3,482 checkpoint rows) | âœ… diffed by `GoldenStateParityTests` |
| Python suite result recorded | âœ… 169 tests |
| Live IQ capture â‰¥ 10 min | âŒ needs the dongle attached to the PC |

### Phase 1 â€” Models, module split, decoder parity Â· **MOSTLY DONE**

| Item | Status |
|---|---|
| Collapse `:app`/`:core:receiver` duplication | âœ… 19 files deleted, zero duplicates |
| Frame-level parity vs Python | âœ… all fixtures match |
| Delete non-antenna sources `[1-src]` | âœ… guarded by `SingleSourceGuardTests` |
| AVR/file replay confined to test source set | âœ… `FileSource` is test-only |
| `:core-test` â†’ `:core:receiver` rename | âœ… Step 3, see Â§7 |
| `:core:model` extraction | â¸ deferred â€” no second consumer yet |
| Port `_resolve_ap_icao` known-ICAO recovery | âœ… ported; proven dead code in the reference, see Â§10 |

Decoder work completed: squawk (`decodeIdentity`), 12-bit and 13-bit Gillham
altitude are ports of the reference. Two dump1090-derived paths (TC23/TC28 squawk)
were removed after the frame harness showed they fabricated a squawk on 731 frames
the reference reports none for. `modeAToModeC` deleted.

### Phase 1.5 â€” Pythonâ†”Android formula audit Â· **AUDIT DONE, two fix rounds DONE**

Full numeric/semantic audit of every ported subsystem against the live Python
reference â€” `docs/correction_plan.md`, 29 findings across decoder formulas,
merge rules, counters, and Android-only additions, each verified by executing
Python directly. Cross-cuts Phases 1/2/3/5, not any single phase, hence the
half-step number. See Â§12 for the first fix round (7 fixes) and Â§13 for the
second (13 fixes). 20 of 29 findings now fixed; 9 remain open.

### Phase 2 â€” Aircraft-state parity and replay harness Â· **DONE**

`GoldenStateParityTests` replays CRC â†’ decode â†’ `AircraftManager` on a virtual
clock and diffs the table at every 10 s checkpoint. Required removing three
wall-clock reads (`AircraftManager.update`, `MessageDecoder.decode`, the CPR frame
stamp) â€” which is precisely why this behaviour had never been testable.

### Phase 3 â€” USB and service lifecycle Â· **PARTIAL**

| Item | Status |
|---|---|
| USB source, live gain, hotplug | âœ… |
| Disconnect detection + auto-reconnect with backoff | âœ… rebuilt (Â§19), confirmed on hardware 2026-07-27 |
| Source watchdog / antenna-loss shutdown | âœ… |
| Aircraft expiry loop | âœ… |
| Demod tuning knobs applied live | âœ… `preambleGapDivisor` / `deltaFloor` in Settings, no reconnect |
| Bias-tee | âœ… live rtl_tcp control command; unverified against real hardware (see Â§5) |
| Formal back-pressure channel + drop counter | âœ… â€” see Phase 4 |
| Lifecycle matrix test (unplug/doze/kill/rotate) | âŒ |

### Phase 3.5 â€” Core receiver profiling and optimization (backend-only) Â· **DONE**

See Â§16 for the full profiling report. Benchmark harness, hot-path allocation
analysis, two targeted optimizations, re-benchmark â€” all executed this session.
Parity harnesses stayed green throughout.

### Phase 4 â€” Receiver dashboard and Live UI Â· **MOSTLY DONE**

| Item | Status |
|---|---|
| Sort order moved out of `AircraftManager` into a presentation-layer `AircraftSort` | âœ… |
| Sort-by setting (Settings page, 6 orders, default first-seen) | âœ… |
| `ReceiverRepository` extracted as its own class (`:core:receiver`, JVM-testable) | âœ… |
| Fixed-rate publish tick + structural diffing | âœ… (inside `ReceiverRepository`) |
| Aircraft-table thread-safety (single dispatcher, was an unenforced 2-way then 3-way race) | âœ… |
| Bounded ingest queue with a real drop counter (`Channel` + `DROP_OLDEST`) | âœ… â€” reads 0 under normal operation by design |
| Rolling accept-rate window (`PipelineStats`, configurable 5â€“60 s, default 10 s) | âœ… |
| Receiver screen: status, accept-rate panel with guidance, pipeline counters incl. drop counter, tuning summary | âœ… â€” own navigation destination via NavigationSuiteScaffold |
| UI models (`AircraftRowUi`, `MapMarker`, `LiveMetrics`, `ReceiverStatusUi`, `DiagnosticEvent`) | âœ… |
| Shared atoms (`StatusStrip`, `MetricTile`, `Sparkline`, `StatusPanel`, `SignalBars`, `FreshnessDot`) | âœ… |
| 5-tab NavigationSuiteScaffold (Traffic, Map, Receiver, Logs, Settings) | âœ… |
| Traffic screen: Live (default) / History / Stats sub-tabs | âœ… Â§33 |
| Live sub-tab: metrics header, sort bar, dense AircraftRow, non-nominal states, start/stop | âœ… |
| Aircraft detail sheet (ModalBottomSheet, freshness dots, diagnosis cards, message timeline) | âœ… |
| Logs screen (Events only) | âœ… |
| MainViewModel rewrite (derived UI flows, diagnostics, sparkline) | âœ… |
| PipelineService public start/stop/reconnect API | âœ… |
| Design-token module (`:core:ui`) | âŒ deferred (ponytail: no second consumer) |
| Rate chart (60 s stacked area) | âŒ |
| 8-sector coverage polar | âŒ |

**Sort-order feature (2026-07-26), by decision.** `AircraftManager.aircraft` used
to return a hardcoded nearest-first order, recomputed by a full sort on every
decoded message â€” a presentation choice baked into domain state, and the exact
kind of thing Step 4's repository layer is meant to fix. That is now split in two:

- `AircraftManager.aircraft` returns **first-seen order** â€” free from
  `LinkedHashMap`, which does not reorder a key on a re-`put`.
- `core/receiver/.../aircraft/AircraftSort.kt` applies one of six
  `AircraftSortOrder` values (`FIRST_SEEN` default, `NEAREST`, `ALTITUDE`,
  `CALLSIGN`, `MESSAGE_COUNT`, `LAST_SEEN`) to a snapshot list. Pure and
  stateless, so it is tested without an `AircraftManager` at all.

Sort lives in `AppConfig.sortOrder`, persisted, selectable in Settings as its own
section â€” **by decision, not yet on the Live/History screens directly.**
`PipelineService.publishAircraft()` applies it once at emission time, and an
explicit `old.sortOrder != newConfig.sortOrder` check re-publishes immediately on
a Settings change, so switching orders doesn't wait for the next frame.
`ConfigChange.requiresPipelineRestart` is untouched â€” sort is presentation-only
and was verified to trigger neither a restart nor a gain/demod reapply.

Default is `FIRST_SEEN`, deliberately: it is the only order that does not
reshuffle rows while someone is reading them, matching the Python reference's
`_update_active_order`, which keeps a stable append-only order for the same
reason. Every other order changes continuously in flight.

### Phase 5 â€” Map, detail, diagnostics, settings Â· **PARTIAL**

| Item | Status |
|---|---|
| Observer position, Fixed/Follow GPS, throttle | âœ… |
| Offline enrichment (ICAOâ†’N-number, airlines) | âœ… 93 % / 56 % measured on real capture |
| Network enrichment â€” metadata (hexdb.io, OpenSky, adsbdb aircraft) | âœ… (FAA CSV removed â€” never used, never generated in Python either) |
| Network enrichment â€” FlightAware scrape (type, airline, route) | âœ… |
| Route lookup (adsbdb) + 24 h cache | âœ… |
| History (departed aircraft, Room, Traffic sub-tab) | âœ… Â§33 |
| Settings screen | âš ï¸ sectioned (Receiver / Sort / Gain / PPM / Tuning / Observer / Auto-stop / Data) but not searchable |
| Landscape layout | âœ… rows fold to one detail line |
| Map | âœ… osmdroid, dark-filtered tiles, range rings on the named range scale, observer, single-overlay markers, trails, label collision, clustering + decimation chip, controls, layers panel, selection sheet |
| Offline maps | âœ… Â§23 â€” Wi-Fi-only downloads, named segments, travel append, safe deletion. 90 tests. Default URL = OSM Mapnik (same as live map). **Toggle** (`offlineDownloadEnabled`, default off) gates all downloads; import from cache always works. |
| Display units setting (mi/nm/km) | âœ… `DistanceUnit` in `:core:receiver`, persisted, threaded through rows/map/coverage |
| Position history for trails | âœ… `AircraftState.positionHistory` (bounded 200, duplicate-suppressed); parity unaffected â€” the harness compares an explicit field allow-list |
| Aircraft detail / `_diagnose()` port | âœ… ModalBottomSheet with freshness dots, diagnosis cards, TCAS, message timeline |
| Logs / diagnostic events | âœ… Events-only screen (DiagnosticEventBuffer ring, severity-colored); History moved to Traffic Â§33 |
| Debug bundle | âŒ deferred to post-v1.0.0 |
| Coverage + performance metrics | âœ… backend + CSV export, see Â§11 â€” no UI (`CoverageCard`/`RateChart` deferred to Phase 4's screen rebuild) |
| CSV export wired to UI | âŒ `CsvExporter`, `PerformanceCsvLogger`, `CoverageCsvLogger` all write files with no in-app entry point to share/view them |

### Phase 6 â€” Performance, battery, reliability Â· **PARTIAL** (see Â§17)

---

## 3. Remaining path

### Step 3 â€” Rename the module Â· **DONE** (2026-07-26)
`:core-test` â†’ `:core:receiver`. See Â§7. The other half of the original Step 3
scope, extracting `:core:model`, remains open â€” no second consumer exists yet
(tracked in Â§5 as low severity, not blocking).

### Step 1.5 â€” Remaining formula-audit findings Â· 9 of 29 still open
20 of 29 `docs/correction_plan.md` findings fixed (Â§12: #1-6,10; Â§13: #7-9,11-19).
9 remain open: #20-22 (counter/rate semantics), #23-29 (Android-only behaviours
with no Python counterpart â€” TC29 decode, surface movement bug, callsign trim,
unbounded confirmedIcaoCache, frameIcao skip-zero). None block current functionality.

### Step 3.5 â€” Core receiver profiling and optimization (backend-only) Â· **DONE**
See Â§16. Two hot-path allocations eliminated, benchmark harness added, parity
green. Written report in Â§16.

### Step 4â€“6 â€” Live UI, Detail, Receiver, Logs Â· **DONE**
NavigationSuiteScaffold, LiveScreen, AircraftRow, AircraftDetailSheet, ReceiverScreen,
LogsScreen, SettingsScreen wrapper, MainViewModel rewrite, PipelineService API â€” all
compiling and building. TextListScreen deleted.

### Step 7 â€” Map Â· **DONE**
osmdroid with an app-private tile cache and a desaturate-then-invert dark filter,
range rings on the 6/12â†’12/25â†’25/50â†’50/100 mi scale, observer, aircraft drawn in a
single overlay (field assignment + `invalidate()` at 2 Hz, never an overlay
rebuild), trails, grid-hash label collision, clustering and a tappable
"showing N of M" decimation chip, controls, layers panel, selection sheet.

**Map bugs found and fixed on-device (2026-07-28):** `Configuration.load()` was
never called so no tile ever downloaded; the range scale was interpreted as
nautical miles, labelling the rings 29/58 instead of 25/50; `computeZoom` fed dp
into a metres-per-*pixel* formula and zoomed out by more than a level; the control
stack had no width so it stretched full-width and rendered against the wrong edge;
and a single aircraft was collapsed into a cluster bubble reading "1".

### Step 8 â€” Remaining UI to spec Â· **in progress**
- Live Â· **DONE** â€” filter chips (Airborne Â· On ground Â· Position Â· Emergency Â·
  `< 50 mi` Â· altitude band), `SORT BY:` dropdown writing `AppConfig.sortOrder` so
  the sort is applied in the repository, metrics-header collapse chevron, metric
  tiles navigating to Receiver, row long-press menu (Show on map Â· Copy ICAO), and
  a "no aircrafts match these filters" panel that reports the tracked count.
  Row layout is now asserted by `AircraftRowLayoutTests` rather than by eye:
  fixed 44/58/42 dp tracks, a Row-owned 12 dp gutter, identity text truncating
  first. `heightIn(min = 72.dp)` replaces the fixed height so the three lines are
  not clipped above a 1.0 system font scale.
- Logs: Frames tab, category chips, search, error-count badge, spec'd row layout
- Settings: search, group restructure (Tuner/Decoder/Demod/Display/Map/Location/
  Power/Logging/Enrichment/Developer), steppers, `RESTARTS RECEIVER` chip
- Detail: three detents, tap-to-reveal provenance, per-DF histogram, frame inspector

**Deferred to post-v1.0.0:**
- Diagnostic bundle export (zip creation, share sheet, privacy opt-in chips)
- Macrobenchmark / FrameTimingMetric / recomposition-count formal testing

### Step 9 â€” Phase 6 validation Â· ~2 days
8 h soak, battery, thermal, offline, window sizes, process death, long-run parity.
**Deferred to a separate build â€” not part of v1.0.0.**

---

## 4. Deliberate divergences from the reference

Each is enumerated in a test and fails loudly if it widens.

| Divergence | Why |
|---|---|
| DF11 interrogator ID | **Reference bug.** `remainder < 80` uses `crc24(whole_frame)` instead of the `CRC(data) XOR PI` syndrome, discarding genuine DF11 replies carrying an interrogator ID |
| TC19 ground speed / track | **Reference bug.** `decode_airborne_velocity` reads the subtype as `(me[0] >> 1) & 7` instead of `me[0] & 7`, so every subsonic ground-speed message is decoded as an airspeed message. In the 44-min capture the reference populates `ground_speed_kts`/`track_deg` **0 times in 3,481 rows**, and `heading_deg` carries 2,574 values derived from the E/W velocity bits. One-character fix in Python |
| DF20/21 Comm-B callsign | Additive, requested in Session 5. The reference never reads the MB field |
| Speed > 700 kt, altitude outside âˆ’1500â€¦72000 ft | Android sanity clamps; the reference has none |

---

## 5. Carried defects and gaps

| Item | Severity | Note |
|---|---|---|
| Live row header tracking cut from `.08em` to `.045em` | Low | Deliberate. `DIST mi` at 9 sp with the spec's `.08em` overflows its 44 dp track once the system font scale is above 1.0 â€” it clipped at 1.15 on the test device. Tracks are sized from the widest value, never from the header, so the header is what gives |
| Altitude-band chip design not specified | Low | The spec lists "altitude band" among the Live chips without naming the bands. Implemented as a chip opening a menu â€” Any Â· `< FL100` Â· `FL100â€“FL250` Â· `> FL250`. Revisit if the intent was something else |
| `"Null"` reached the UI as an operator name | **FIXED** 2026-07-28 | hexdb.io returns the literal string `Null` rather than omitting `registered_owners`. Every upstream field now goes through `present()`, guarded on cache read as well as on parse, since the 30-day cache already held such rows |
| Dongle delivers the rtl_tcp greeting but no IQ | **RESOLVED (hardware)** 2026-07-28 | Was: driver opened the device cleanly but only the 12-byte greeting arrived, verified with the app out of the picture (`am start` + `nc`, 0 bytes in 15 s). Cleared by a physical replug â€” `adbd` is not root on this build, so nothing in software could reset the endpoint. Kept here because the entry below is the software fault that can wedge it again if it regresses |
| Orphaned pipeline job could brick the dongle | **FIXED** 2026-07-28 | `startPipelineInternal()` overwrote `pipelineJob` without checking the old one was dead, so two jobs raced. The loser fired a second `iqsrc://` a couple of seconds after the winner had opened the device; the driver failed that open with `LIBUSB_ERROR_BUSY` and tore down the *working* session on its way out, leaving the dongle unopenable until the driver app was force-stopped by hand. Guarded in `startPipelineInternal()`; stop/start/restart now serialise through `sessionLock` and `teardownSession()` awaits the socket close instead of firing it and forgetting; `onDestroy` closes the socket before cancelling the scope; `LIBUSB_ERROR_BUSY` now surfaces `DRIVER_BUSY_MESSAGE` instead of claiming no dongle was found |
| Test suite not audited for vacuous assertions | Medium | three found and removed so far |
| No CRC-valid Comm-B on air yet | Low | BDS decode still synthetic-only |
| TCAS RA decode never exercised on air | Low | `decodeRaMv` is gated on `mv[0] == 0x30` (BDS 3,0), so routine DF16 traffic never reaches it. Needs an actual resolution advisory â€” rare enough that passive observation may never produce one. Synthetic vectors + Python parity only. Not blocking |
| Bias tee unverified on real hardware | Low | command encoding unit-tested; never sent to a live dongle â€” no LNA on hand to confirm rtl_tcp_andro+ honours `0x0e` |
| 9 of 29 `docs/correction_plan.md` findings still open | Lowâ€“Mixed | 20 fixed (Â§12+Â§13). Remaining: #20-22 (counter/rate semantics), #23-29 (Android-only behaviours). No missing Python-parity fields remain. |
| `:core:model` not yet extracted | Low | no second consumer of model-only types exists yet (`:core:ui`/`:data` are Phase 4/5) |
| Map never showed the live GPS fix in Follow-GPS mode | **FIXED** 2026-08-01 | `PipelineService` resolved the live fix internally (`ObserverPositionResolver`, feeding the decoder/`AircraftManager`) but never published it upward — `MapScreen`'s center/"Follow observer" button read `AppConfig.observerLatitude/observerLongitude` directly, which in Follow-GPS mode are only the fallback coordinates, so the map stayed pinned to the fallback both on app start and on tap. Added `PipelineService.resolvedObserverPosition` (`StateFlow<Pair<Double,Double>>`, updated in `applyObserverPosition()`), threaded through `MainActivity` → `MainViewModel.observerPosition` → `MapScreen`'s `observer` `GeoPoint`. Not yet confirmed on device — no Follow-GPS hardware pass since |
| Settings → "Manage offline maps" closed the app | **FIXED** 2026-08-01 | Root cause found via `adb logcat -b crash`: `AndroidNetworkEligibility.currentState()` calls `ConnectivityManager.getActiveNetwork()`, which throws `SecurityException` without `android.permission.ACCESS_NETWORK_STATE` — never declared in the manifest. Thrown from `OfflineMapsViewModel.init { refresh() }`, i.e. the instant the screen opens. Added the permission to `AndroidManifest.xml`; confirmed on device (Samsung SM_S928B) — screen now opens cleanly. Along the way, also fixed a real hazard in the same function: `refresh()` ran the manifest load and `storageUsage()` (a `File.length()` stat per stored tile key) synchronously on the caller's thread; now dispatches on `Dispatchers.IO`. New Robolectric test `OfflineMapsScreenSmokeTest` (real `FileManifestStore`/`FileTileStore`/`OfflineMapManager` adapters, no mocks) covers empty-state, the download sheet, and select → delete → confirm with a real segment |
| Live screen "Install" button (driver-not-installed state) was a no-op | **FIXED** 2026-08-01 | `onClick = {}` — found during a full UI-control audit. `DriverNotInstalledPrompt` already had working Play-Store-intent logic for the same purpose; extracted to `UsbHotplugReceiver.openDriverInstallPage()` and both call sites now use it |
| Live screen "View logs" button (receiver-error state) navigated to Receiver, not Logs | **FIXED** 2026-08-01 | Wired to `onNavigateToReceiver`, same as the identical action in the Running state. Relabeled to "Open Receiver" to match what it actually does, rather than rewiring navigation and guessing at intent |
| Live screen overflow → "Reset counters" was a no-op | **FIXED** 2026-08-01 | `onClick = { showOverflowMenu = false }` only closed the menu. `PipelineStats.reset()` already existed and is used internally on reconnect (`clearSessionState()`); added `PipelineService.resetStatsCounters()` and wired the menu item to call it |
| Settings screen "Close" (✕) and "Done" buttons were both no-ops | **FIXED** 2026-08-01 | The public `SettingsScreen()` composable never declared an `onBack` parameter, so the private content composable's `onBack: () -> Unit = {}` always used its empty default — tapping either control did nothing, on the exact two actions ("Close", "Done") the audit was asked to verify. Added the parameter, wired at the `MainActivity` call site to navigate back to the Live tab using the same `popUpTo`/`launchSingleTop`/`restoreState` pattern already used for every other bottom-nav move in that file |
| History screen "Clear" has no confirmation | **Flagged, not changed** 2026-08-01 | Irreversibly deletes all Room-backed aircraft history on a single tap, unlike the Offline Maps delete flow (`DeleteConfirmDialog`) or Receiver's STOP-while-running (`AlertDialog`), which both confirm. Left as-is pending a product decision — could be intentional given the list regenerates from live traffic |
| `AircraftDetailScreen.kt` (356 lines) is dead code | **Flagged, not changed** 2026-08-01 | Defined but never called from production code — superseded by `AircraftDetailSheet` (the bottom sheet), per `MapScreen.kt`'s own comment: "there is one detail implementation, not a second one living on the map." Only other reference is a historical note in `PHASE_PROGRESS.md`. Left in place pending confirmation it's safe to delete |

---

## 6. Open decisions

1. **Map library** â€” recommendation: osmdroid (offline, no key, no Play Services). Blocks Step 7.
2. **Registration database** â€” algorithmic covers 93 % of observed traffic; open question is whether to bundle an offline dataset for non-US aircraft. Blocks part of Step 8.
3. **Distance units** â€” UI shows nautical miles, the Python console shows miles. Settle before Step 5.
4. **Fix the two reference bugs in Python?** Both are one-line changes in `D:\SDR\adsb_v9_5`. Untouched so far â€” Python is read-only by instruction.


---

## 7. To be done in the future

Work that is required but deliberately postponed. Deferred is not cancelled: each
item stays here until it is done or explicitly dropped.

### Step 3 â€” Rename `:core-test` â†’ `:core:receiver` â€” **DONE (2026-07-26)**

Deferred 2026-07-25 by Avi's decision, done 2026-07-26 on request. The module
that contains the entire production receiver â€” demodulator, CRC, decoder,
aircraft state, enrichment â€” is now named `:core:receiver` instead of the
test-sounding `:core-test` that let a stale duplicate constant certify a
sample-rate bug on hardware in Sessions 7-8 (see the old README, no longer
present, for the full incident).

**What moved.** `core-test/src` â†’ `core/receiver/src` (package names
unchanged â€” `com.laviavi.adsbandroid.*` â€” only the Gradle module path and
directory changed). `settings.gradle.kts`: `include(":core-test")` â†’
`include(":core:receiver")`. `app/build.gradle.kts`:
`project(":core-test")` â†’ `project(":core:receiver")`. Path references updated
in `tools/phase0_goldens.py`, `tools/gen_airlines.py`,
`tools/gen_registration_parity.py`, and one comment in `UsbRtlSdrSource.kt`.
The three mitigation artifacts (`core-test/README.md`, its `build.gradle.kts`
header, the `settings.gradle.kts` note) are deleted/replaced â€” the name no
longer needs contradicting.

**Verified:** `GoldenFrameParityTests` and `GoldenStateParityTests` both still
pass post-move; 267 `:core:receiver` + 29 `:app` tests, 0 failures â€” identical
counts to before the move, confirming it changed no behaviour.

**Not done, by design.** The other half of the original Step 3 scope â€”
extracting `:core:model` (AircraftState/DecodedMessage/RawFrame/enums into
their own zero-Android-dep module) â€” was **not** done alongside the rename.
`:core:receiver` already has zero Android dependencies; the model split's only
purpose is letting a second module (`:core:ui` or `:data`, both Phase 4/5, both
not yet built) depend on the types without pulling in the receiver logic too.
Splitting now would be modularising for a consumer that doesn't exist yet â€”
tracked in Â§5, not blocking, revisit when `:core:ui` or `:data` is actually
started.

### Formal back-pressure channel + drop counter â€” **DONE, see Â§10**

Landed together with the `ReceiverRepository` extraction, as planned below:
bounded `Channel` (`DROP_OLDEST`, capacity 64) between the IQ loop and the
aircraft table, with a real drop counter surfaced on the Receiver tab. No
longer deferred.

### Deferred from v1.0.0 (2026-07-28, by decision)

| Item | Reason |
|---|---|
| Diagnostic bundle export (zip + share sheet + privacy opt-in chips) | Scope â€” useful debugging tool but not part of the core receiver console |
| Macrobenchmark / `FrameTimingMetric` / recomposition-count formal testing | Separate build â€” requires `:benchmark` module + physical device test infrastructure |
| Phase 6 validation (8h soak, battery, thermal, offline, compatibility) | Separate build â€” hardware-intensive, not gated by code changes |

---

## 8. Fixed-rate publish + thread-safety fix (2026-07-26)

**What changed.** `PipelineService` published the aircraft list once per decoded
message â€” sorted, at frame rate. Session 7's own capture logged ~870 msg/s on
real hardware, so that was up to 870 full sorts a second to feed a UI that
cannot render faster than a handful of Hz. `startAircraftPublishLoop()` now
reads and sorts the table on a fixed 4 Hz tick, and only emits when the sorted
list differs from what was last published (`AircraftState`/`List` already have
structural `equals()`, so this is a plain `!=` â€” no new diff type needed).
`publishAircraft()` is kept, now `suspend`, for the one case that should not
wait for the next tick: an explicit sort-order change in Settings.

**A real bug surfaced while wiring the ticker in.** `AircraftManager`'s own doc
comment says it needs external synchronization ("use a single-threaded
dispatcher"), but nothing enforced that. The message path and
`startExpiryLoop()` each ran as independent `serviceScope.launch` coroutines on
`Dispatchers.Default` â€” a multi-threaded pool â€” so both could already run
concurrently and race on the same `LinkedHashMap` before this change. Adding the
ticker as a third accessor would have made the race three-way instead of fixing
it. Addressed with `Dispatchers.Default.limitedParallelism(1)`: every read or
write of `aircraftMgr` (message path, expiry, route-enrichment callback, restart
reset, the new ticker) now goes through that one dispatcher.

**Not done, on purpose:** the logic is still inline in `PipelineService`, not
extracted into a standalone `ReceiverRepository` class, and there is no formal
back-pressure channel with a drop counter. Frames are still processed
synchronously in the IQ read loop with no async hand-off, so there is no queue
that could actually back up â€” building drop-counting infrastructure for a queue
that does not exist would be speculative. Revisit if/when Step 4's
`ReceiverRepository` extraction happens and introduces a real queue boundary.

**Verified:** 240 core-test + 16 app tests, 0 failures; frame/state parity
unaffected (26,760 frames / 3,484 rows still match Python). Confirmed on the
Pixel 6: live list updates normally, History persists across app restart,
uptime and message counters climb continuously, no crash.


---

## 9. Accept-rate window + Receiver tab (2026-07-26)

**`PipelineStats`** gained a rolling accept-rate window: tested / accepted /
corrected-counts-as-accepted / rejected over the last `windowSeconds` (5â€“60,
default 10), recomputed each 1 Hz tick from a ring buffer of per-second deltas.
This is the number that would have shown Session 7's failure in seconds â€”
"tested" climbing while "accepted" stays at zero is a decode-pipeline problem,
not an antenna problem, and the two mean different next steps. Verified with a
test shaped exactly like that incident (50,000 frames tested, 0 accepted).

**New Receiver tab** (`ui/receiver/ReceiverScreen.kt`), added as a third tab
alongside Live/History rather than a new destination â€” there is no adaptive nav
shell yet to add one to (that is Step 5). Shows: source status and tuner,
the accept-rate panel with evidence-tied guidance text (never asserts an antenna
fault â€” that's not evidence this screen has), pipeline counters, and the current
tuning values with a link back to Settings â†’ Tuner rather than duplicating the
sliders.

**Verified:** 240 core-test + 23 app tests (7 new for the window), 0 failures,
parity unaffected. Deployed to the Pixel 6; logcat confirms a clean start with
the ticker and gain/tuning lines present. **Visual confirmation of the Receiver
tab on-device is outstanding** â€” the phone was locked behind fingerprint auth at
deploy time and remote unlock is out of bounds. Needs a look on the device.


---

## 10. Steps 1-5 of the non-UI backlog (2026-07-26)

Executed in order per Avi's numbered list (finish Step 4's repository half, Room
migrations, signal level, `_resolve_ap_icao`). Each verified with new tests and
on-device confirmation before moving to the next.

**1. `ReceiverRepository` extraction + back-pressure.** Moved out of
`PipelineService` into `:core-test` (`aircraft/ReceiverRepository.kt`) â€” owns the
aircraft table, the 4 Hz publish tick, expiry, and a bounded `Channel` ingest
queue (`DROP_OLDEST`, capacity 64) with a real drop counter via the channel's own
`onUndeliveredElement`, not a hand-rolled one. 9 new tests, including a
deterministic overflow test (fills the channel without starting the consumer) and
a "healthy pipeline drops nothing" guard. Surfaced on the Receiver tab as
"Dropped (backpressure)" â€” reads 0 on hardware, as expected: there is no real
backlog at today's frame rates, the queue exists so there would be visibility if
that ever changed. Found and fixed a real gap while wiring it in: `setLookup()`
(the ICAO database load) had no path onto the new repository and would not have
compiled.

**2. Room migrations, replacing `fallbackToDestructiveMigration()`.** Added
`MIGRATION_1_2` (creates `aircraft_seen`, absent before Session 9) and
`MIGRATION_2_3` (squawk INTEGER -> TEXT, Session 10's fix; old hex-packed values
are dropped rather than stringified into a plausible-looking wrong value).
Verified with Robolectric + `room-testing` (new test dependencies) against a
byte-correct v1 database built via Room itself, not hand-transcribed SQL â€” the
whole point being that Room's own schema-hash validation catches a wrong
migration rather than the test's setup being the thing that's wrong. Also enabled
`room.schemaLocation` so this gap doesn't recur at v4.

**3. `signalDbfs` populated on the USB path.** Root cause: `Demodulator`
already computed a correct per-frame signal level, but `PipelineService`
discarded it (`frames.map { it.bytes }`) and rebuilt a bare `RawFrame(bytes)`
with the 0.0 default before CRC checking. Fixed by passing the original
`RawFrame` through unchanged. Also fixed the display, which would have kept
showing "0%" even once populated (`"%.0f"` on a 0.0-1.0 ratio). Confirmed on
hardware: Signal now reads real per-aircraft percentages (5-21% observed).

**4. `_resolve_ap_icao` ported â€” and found to be dead code in the reference.**
While porting this DF16 intruder-ICAO heuristic, discovered the entire DF16 (TCAS
long air-air surveillance) decode path had never been implemented at all â€”
`LongAirAir` carried zero fields. Ported the whole thing: SL, altitude, BDS 3,0
RA decode (`_decode_ra_mv`), and the AP-field intruder heuristic, plus DF0's
vertical-status (`onGround`) bit, also previously unset. `AircraftState` gains
`tcasSl`/`tcasRaActive`/`tcasRaText`/`tcasRaComplement`/`tcasRaTerminated`/
`tcasTargetIcao`/`tcasEventCount`; `AircraftManager` gains the reference's
rising-edge event-counting merge logic.

`_resolve_ap_icao` itself is confirmed, empirically, to be unreachable: XOR is
its own inverse, so whenever `AP == a XOR b` for two tracked ICAOs, *both*
directions match simultaneously, making the match count always even â€” never the
single match the reference's own `len(matches) == 1` check requires. Verified
against the live Python reference: 0 non-null results across 200,000 randomised
trials. Ported faithfully (bug-for-bug) rather than "fixed," per this project's
parity-over-improvement standard; `tcasTargetIcao` will always be null on both
sides. Documented in the Kotlin source and asserted by a test running the same
property check.

266 core-test + 25 app tests (up from 240 + 16), 0 failures. Frame/state parity
unaffected. Deployed and verified on hardware after every step; DF16/TCAS RA
paths themselves are unverified on live air (no such traffic seen this session,
noted in Â§5).

**5. Bias-tee support.** rtl_tcp's `set_bias_tee` command is `0x0e`, same 5-byte
big-endian struct as the existing gain commands (`RtlTcpGain.CMD_SET_BIAS_TEE`).
Sent live over the control channel â€” no reconnect â€” from `UsbRtlSdrSource.
applyBiasTee()`, following `applyGain()`'s exact shape. Wired through
`AppConfig.biasTee` (default off â€” a powered-wrong bias tee can damage an
unpowered antenna, so it never defaults on), persisted in `AppConfigStore`, and
a new `ConfigChange.requiresBiasTeeReapply` check alongside gain's â€” bias tee is
a live command like gain, not a connect-time parameter like PPM, so it does not
restart the pipeline. Applied both on a Settings change and once on initial USB
connect (`onUsbConnected`). Settings UI: a `SwitchRow` in the Gain page under a
new "Bias tee" section with an explicit hardware-risk warning.

The Python reference (`config.py`'s `bias_tee` flag) validates that the feature
belongs in this app's config surface, but calls `pyrtlsdr`'s direct
`sdr.set_bias_tee()` â€” a different transport that never speaks rtl_tcp's wire
protocol. The `0x0e` command byte is instead ported from osmocom's own
`rtl_tcp.c`, so this is new ground relative to the reference, not a port with a
parity harness. Command encoding is unit-tested (`RtlTcpGainTests`); actually
toggling it against a live dongle is **not verified** â€” no LNA on hand, and
enabling bias tee on the wrong antenna can damage it, so this was deliberately
left for Avi to confirm on the actual hardware setup rather than testing blind.

267 core-test + 29 app tests (up from 266 + 25), 0 failures. `:app:assembleDebug`
succeeds; debug build installed on the Pixel 6, confirmed the process starts
cleanly, but a live call on the phone at deploy time meant the Settings screen
itself was not walked in the UI this session â€” outstanding, see the summary.

---

## 11. Coverage and performance metrics â€” backend + CSV only (2026-07-26)

By decision: computation and CSV export only, no `CoverageCard`/`RateChart` UI â€”
those are designed as part of Phase 4's Receiver-screen rebuild, which hasn't
happened yet, so building them now would mean redoing them against the real
scaffold later.

**Ported** `observability/performance.py` and `observability/coverage.py`
faithfully into `:core:receiver` (`observability/PerformanceMetrics.kt`,
`observability/CoverageMetrics.kt`), pure and JVM-testable, plus thin `:app`
file-writing wrappers (`PerformanceCsvLogger`, `CoverageCsvLogger`) that mirror
`RawMessageLogger`'s existing day-rotating-file pattern.

**Counting-policy divergence found and fixed while porting, not after.**
`core.py:_process_raw` increments Python's `valid` counter for *every* usable
frame â€” valid, corrected, **and** recovered all set `valid=1`, with corrected
and recovered also counted a second time as their own breakout columns. The
existing Kotlin `PipelineStats` didn't preserve that: `RECOVERED` frames were
folded into `validMessages` with no way to isolate them, and `invalidMessages`
conflated genuinely-bad CRCs with parity-address frames (which Python counts
toward neither `bad_crc` nor `valid` â€” only `total`). Both would have produced
a wrong `msgs_noise`/`msgs_recovered` column. Fixed by adding two new counters
(`recoveredMessages`, `badCrcMessages`) alongside the four existing ones in
`PipelineStats` â€” additive only, the four UI-facing counters and their on-screen
meaning are unchanged.

**FlightAware counters are dropped, not ported** â€” matches this project's
existing decision not to port the FA scraper at all (Â§3.3 of the migration
plan: ToS/fragility/battery). The three `fa_queries_*` columns stay in the CSV
for name-and-order parity, always `0`; `fa_scraper_degraded` can structurally
never fire. Asserted by test, not silently absorbed.

**Coverage reuses `AircraftManager`'s existing `distanceNm`/`bearingDeg`**
(already computed on every ADS-B position merge, identical haversine formula
and 3440.065 nm Earth radius to `enrich/distance.py`) rather than recomputing
distance/bearing a second time â€” the aggregation in `CoverageMetrics` is pure
sector/percentile/symmetry math over values already proven correct elsewhere.

**A real rounding divergence was caught empirically, not theoretically.**
`_symmetry_score`'s Python `round()` uses round-half-to-even; a naive Kotlin
`Math.round()` (round-half-up) would disagree at an exact `.5` tie â€” and
running the actual Python function surfaced a concrete, common case that hits
exactly that tie: reception in exactly one of 8 sectors scores `100 * 1/8 *
1.0 = 12.5`, which the reference rounds to 12 and round-half-up would round to
13. Fixed with `Math.rint()` (also round-half-to-even), verified against six
Python-executed ground-truth values including this one, not hand-derived.

**No full CSV column-diff harness was built** (unlike `GoldenFrameParityTests`/
`GoldenStateParityTests`) â€” that would column-diff a live Python run against a
live Android run over identical input, a materially bigger task than asked for
today. Instead, every formula (bearing sector boundaries, percentile
interpolation, symmetry score, counting policy, diagnosis-hint priority order)
was individually verified against the real Python reference executed directly,
33 new `:core:receiver` tests plus 2 `PipelineStats` tests. Flagged here in
case exact byte-for-byte CSV parity is wanted later â€” that's a distinct,
larger follow-up.

**Verified on hardware, with real data, not just unit tests.** Deployed to the
Pixel 6 mid-session; `performance_2026-07-26.csv` and `coverage_2026-07-26.csv`
both appeared in app-private storage with correct headers and live values
(e.g. one real row: 1,139 msgs/60s, 40.0% decode success, `high_noise` hint
correctly fired; a coverage row with 6 positioned aircraft, real per-sector
mile distances, symmetry score 34). No crash. Confirms the full tick â†’ compute
â†’ format â†’ file-write path end to end, not just the pure math.

300 `:core:receiver` + 31 `:app` tests (up from 267 + 29), 0 failures, parity
harnesses unaffected. Files write to app-private external storage with no
in-app way to view or share them yet â€” that's the CSV-export-entry-point item
still open in Step 8.

---

## 12. Pythonâ†”Android formula audit + first fix round (2026-07-26)

**The audit.** `docs/correction_plan.md` â€” a full numeric/semantic re-check of
every ported subsystem (demod, CRC, decoder, aircraft merge, enrichment,
location, observability, stats, config) against the live Python reference,
each finding verified by executing Python directly, not by reading alone. 29
findings: 7 wrong formulas, 12 missing/mis-scoped logic, 3 counter/rate
mismatches, 7 Android-only behaviours with no Python counterpart. Full detail
lives in that file; this section covers what was fixed today.

**Fixed â€” #1 DF11 II code.** Was `bytes[6] and 0x0F` (raw low nibble of the
last PI byte â€” no XOR, no CRC, wrong bits; disagreed with the reference on
18,764/20,000 sampled values). Now `(addr ushr 20) and 0x0F` where `addr` is
`CrcChecker.computeCrc(frame.bytes)`, algebraically `PI XOR CRC24(DF+CA+ICAO)`
â€” matches `adsb_decoder.py`'s `addr = pi ^ crc24(data[:-3]); ii_code =
(addr >> 20) & 0x0F` exactly.

Found a structural property while fixing this, verified against the live
Python reference (constructed frames with intended ii=1/3/7/15, ran them
through the real `CRCChecker`): **`ii_code` is mathematically always 0 for
any DF11 the reference's own CRC gate classifies VALID or RECOVERED.** Both
acceptance paths require `addr == 0` or `addr < 80`; `ii_code` only reads the
top 4 bits (`>> 20`), and 80 is far below `2^20`, so those bits can never be
nonzero on a frame the gate lets through. A DF11 carrying a genuine
interrogator ID produces a large `addr` and is rejected as `BAD` before
decoding ever runs â€” confirmed empirically, not assumed. Ported faithfully
(the correct formula) rather than special-cased to produce a more interesting
number, per this project's parity-over-improvement policy. Tests split
accordingly: the formula is verified in isolation (an injected `crc` proves
`(crc>>20)&0xF` is computed right), and a separate test proves the
real-world constraint (nonzero-ii frames never reach the decoder at all).

**Fixed â€” #2 TC20-22 GNSS altitude.** Was routed through the same
Gillham/Q-bit 12-bit decode as barometric altitude and written into
`altitudeFt` â€” wrong formula (GNSS altitude has no Gray-code structure, it's a
plain 25-ft-step count) *and* wrong field (silently overwrote barometric
altitude on merge). Split `decodeAirbornePosition` into
`decodeAirbornePositionBaro` (TC9-18, unchanged) and
`decodeAirbornePositionGnss` (TC20-22, new): `alt_raw * 25 - 1000` with the
full 13-bit mask (`0x1FFF` â€” no Gillham decode here to absorb a stray 13th
bit, unlike the barometric path), written to a new `altitudeGnssFt` field on
`AdsbFields`/`AircraftState`, merged non-destructively alongside (not
instead of) `altitudeFt`.

**Fixed â€” #5 / #6 TC31 NACp and SIL wrong bytes.** Both previously read from
`f.bytes[9]`/`f.bytes[10]` (`me[5]`/`me[6]`) â€” the wrong bytes entirely â€” and
`sil` had a subtype-dependent branch (different formula for airborne vs
surface) that `_decode_operational_status` doesn't have at all: the reference
computes `version`/`nac_p`/`nac_v`/`sil` identically regardless of subtype.
Now `nacP = f.bytes[7] and 0x0F` (`me[3]`) and `sil = (f.bytes[8] ushr 1) and
0x03` (`me[4]`), both unconditional. `nac_v` (`(me[4]>>3)&0x07`) is in the
reference but still not decoded â€” out of scope for this round.

**Fixed â€” #10 sticky ground flag.** `AircraftManager.mergeAdsb` only wrote
`onGround` when `msg.typecode in 5..8` (surface messages) â€” an airborne
report (TC9-18/20-22) never touched it, so a departing aircraft stayed "on
ground" forever once a surface message had set it true. `AdsbFields.onGround`
changed from `Boolean = false` to `Boolean? = null` (null = this TC doesn't
report ground status; TC1-4/19/29/31 never touch it), and the merge is now a
direct `f.onGround ?: state.onGround` â€” matches the reference's `if
msg.on_ground is not None: state.on_ground = msg.on_ground`. `TC9-18` and
`TC20-22` both explicitly set `false`, so either now clears a stale `true`.

**Fixed â€” #3 / #4 TC19 velocity fields.** Subtype 3/4 (airspeed) was writing
into `groundSpeedKt`/`trackDeg` â€” airspeed reported as ground speed, magnetic
heading reported as true track (the ratio itself was already correct: `45/128
== 360/1024`). New `AdsbFields`/`AircraftState` fields `airspeedKt`,
`headingDeg`, `speedType` (`"ground"` | `"airspeed_ias"` | `"airspeed_tas"`,
matching the reference's own tag), kept distinct from the *existing*
`trueAirspeedKt`/`magneticHeadingDeg` fields (those are Comm-B/BDS-sourced,
a different message family entirely â€” reusing them would have traded one
field-conflation bug for another).

**Verification.** All 7 fixes are new-formula ports, not just moved code, so
each is covered by targeted unit tests using Python-verified ground truth
(not hand-derived) â€” including the two pre-existing tests
(`II code is lower nibble...`, `II code masks upper nibble`) that had
asserted the *old, wrong* formula and were rewritten rather than deleted.
302 `:core:receiver` tests (up from 300), 0 failures; both golden parity
suites still pass unaffected â€” `comparedFields` in `GoldenStateParityTests`
doesn't currently check `on_ground`/`nac_p`/`sil`/`interrogator_ids`/
`altitude_gnss_ft`/`airspeed_kts`/`heading_deg` at all (a pre-existing gap,
not something this round closed â€” see below), so these fixes needed their
own dedicated tests to be exercised at all. Deployed to the Pixel 6
afterward: live decode through every modified path (DF11, TC19, TC20-22,
TC31, the ground-flag merge) ran cleanly for the observation window, no
crash, no decode exceptions.

**Not done, deliberately out of scope for this round:**
- **Expanding `GoldenStateParityTests`' `comparedFields`** to actually check
  the fields these fixes touch. The golden fixture TSVs already *contain*
  `on_ground`/`nac_p`/`sil`/`interrogator_ids`/`altitude_gnss_ft` â€” they're
  just never compared â€” but wiring them in risks surfacing *other*, unrelated
  known divergences (e.g. `interrogator_ids` is a Python *set*, rendered
  `"3;7"`; Kotlin's `iiCode` is a scalar, last-wins â€” item #15, not fixed
  today) as new failures unrelated to this round's formulas. Left as a
  distinct, larger follow-up rather than entangled with these 7 fixes.
- **#7 TC31 NACv** â€” genuinely missing (not a wrong byte), not in the
  requested fix list.
- **#9 DF16 `onGround = False`** â€” same sticky-flag family as #10 but a
  separate reference line (`decode_tcas_df16`), not requested this round.
- The other 22 correction-plan findings â€” tracked in Â§5 and Â§3 (Step 1.5),
  not scheduled.

**Documentation defects the audit itself found (Â§8 of correction_plan.md):**
this document previously claimed per-aircraft message history was ported
(item #17) â€” it is not; and `ANDROID_MIGRATION_PLAN.md`'s deliberate-divergence
table didn't list the TC29 decode (item #23), an Android-only addition
(including synthesising emergency squawks 7500/7600/7700 that the reference
never does). Both noted here; neither fixed today (no code exists yet for
#17, and #23 is a documentation gap in a different file, not this round's
scope).

---

## 13. Second formula-audit fix round (2026-07-26)

Completed all 13 remaining correction-plan items #7â€“#19 in a single pass, using
the Python reference as source of truth for every formula.

**Fixed â€” #7 TC31 NACv.** Added `nacV = (f.bytes[8] ushr 3) and 0x07` in
`decodeTC31`, matching `nac_v = (me[4] >> 3) & 0x07`. Field was entirely missing.

**Fixed â€” #8 DF4 flight-status ground flag.** `decodeDF4` now computes
`fs = (bytes[0] ushr 2) and 0x07` and `onGround = fs in (1, 3, 5)`, matching
`_decode_altitude_reply`'s logic. Two existing tests that asserted DF4 *never*
set `onGround` were rewritten to assert the correct Python-parity behaviour.

**Fixed â€” #9 DF16 onGround.** `LongAirAir` now carries `onGround = false`
(matching `msg.on_ground = False` in `decode_tcas_df16`), merged via
`mergeLongAirAir`.

**Fixed â€” #11 TC1-4 emitter category.** TC1-4 branch now sets
`emitterCategory = (tc - 1) * 8 + (f.bytes[4] and 0x07)`, matching
`emitter_category = (tc - 1) * 8 + (me[0] & 7)`. Field existed on
`AircraftState` but was never written.

**Fixed â€” #12 CPR tie-breaking.** Changed `useOdd = odd.timestampMs >= even.timestampMs`
to strict `>`, matching Python's `is_odd_latest = odd_ts > even_ts`. On a tie,
both sides now pick even.

**Fixed â€” #13 CPR rounding.** `mergeAdsb` now rounds lat/lon to 6 decimal places
via `Math.round(it * 1_000_000.0) / 1_000_000.0`, matching `round(lat, 6)`.
Note: uses round-half-up (`Math.round`) vs Python's round-half-to-even â€” a
sub-meter difference at the 6th decimal, unlikely to matter in practice.

**Fixed â€” #14 CPR state purge.** Added `MessageDecoder.purgeCpr(icao)`, called
from `AircraftManager.expireStale()` via a `decoder` reference wired through
`ReceiverRepository.setDecoder()` in `PipelineService.onCreate()`. CPR maps
are no longer unbounded and cannot pair against frames from a previous session.

**Fixed â€” #15 II codes as set.** Added `interrogatorIds: Set<Int>` to
`AircraftState`, accumulated in `mergeAllCall`. Rendered as `"3;7"` via
`interrogatorIdsStr`, matching Python's `interrogator_ids` set. Scalar `iiCode`
retained for backward compatibility.

**Fixed â€” #16 Signal averaging.** Added `signalHistory: List<Double>` (bounded
to 20, matching `deque(maxlen=20)`) with computed `avgSignal` property. Updated
in `mergeMessage`.

**Fixed â€” #17 Per-aircraft message history.** Added
`messageHistory: List<MessageSummary>` (bounded to 50, matching
`deque(maxlen=50)`) with `MessageSummary` data class carrying timestamp, DF,
TC, CRC result, and signal level. Updated in `mergeMessage`.

**Fixed â€” #18 Per-aircraft CRC counters.** Added `validCount`, `correctedCount`,
`badCrcCount`, and `lastPositionMs` to `AircraftState`, computed in
`mergeMessage` per the reference's counting policy.

**Fixed â€” #19 Frame timestamp.** `RawFrame` now carries `sampleOffset: Int`,
set by the demodulator to the sample index `j` within the buffer.
`PipelineService.processFrames` computes per-frame timestamps as
`bufferBaseMs + sampleOffset * 1000L / REQUIRED_SAMPLE_RATE_HZ`, matching
Python's `timestamp + j / _SAMPLES_PER_SEC`.

**Verification.** 302 `:core:receiver` + 31 `:app` tests, 0 failures.
`:app:assembleDebug` succeeds. All golden parity suites still pass. No new
dedicated tests were added this round â€” the fixes are structural (new fields,
corrected formulas) and exercised by the existing test suite.

---

## 14. Signal strength + gain guidance (2026-07-26)

**1. True dBFS conversion.** `signalDbfs` on `AircraftState` was a linear 0â€“1
ratio despite the field name. Now stores `20 * log10(ratio)`, clamped to
[-40, 0] â€” matching `decoder/models.py`'s RSSI display formula. Conversion
happens in `AircraftManager.mergeMessage` via a new `toDbfs()` companion
function. `AircraftRow` updated from "Sig: 5%" to "Sig: -13.0 dBFS".

**2. Strong-signal gain guidance.** `PipelineStats` tracks `strongSignalCount`
(frames where dBFS >= -3, the dump1090-fa/wiedehopf ADC full-scale threshold).
`PipelineStats.Snapshot.strongSignalPct` derived as `strong / valid * 100`.
Receiver tab shows two new guidance rules: >7% strong â†’ "consider lowering SDR
gain"; <0.5% strong â†’ "gain may be too low". Rules fire only after 100 valid
frames to avoid false signals during startup.

**3. Per-sector median signal in coverage CSV.** `PositionedAircraft` now
carries `signalDbfs`. `CoverageMetrics.computeRow` collects dBFS per sector and
computes `medianSignalDbfs`. New CSV column `sector_X_median_signal_dbfs` for
each of the 8 sectors. Enables the two-axis diagnostic: short range + weak
signal â†’ obstruction; short range + normal signal â†’ low traffic density.

**4. Sparkline data buffer.** `MAX_SIGNAL_HISTORY` bumped from 20 to 60 to
support the per-aircraft signal sparkline (Phase 4.2 of the plan). `avgSignal`
still averages the full window; new `avgSignalDbfs` computed property converts
to dBFS for display. Sparkline rendering itself is UI-only, deferred to
Step 5's adaptive scaffold.

302 `:core:receiver` + 31 `:app` tests, 0 failures. Deployed to the Pixel 6.

---

## 15. Full enrichment pipeline (2026-07-26, v0.7.0) + FAA removal (2026-07-27)

Active enrichment sources: hexdb.io, OpenSky, adsbdb aircraft, FlightAware scraping.

**Source priority:**
- US aircraft (ICAO prefix A): hexdb.io â†’ OpenSky
- Non-US aircraft: hexdb.io â†’ OpenSky â†’ adsbdb aircraft
- FlightAware scrape overrides all other sources for type/airline/route

**FAA CSV removed (2026-07-27).** `FaaDatabase.kt` deleted; `faa_aircraft` Room
table dropped via `MIGRATION_4_5`; DB version bumped 4 â†’ 5. Root cause: an earlier
session's implementation plan (`calm-gliding-cosmos.md`) framed the task as
"port Python's full enrichment pipeline identically" and added FAA even though
the migration plan classified it "Lib/Defer P2" and `faa_aircraft.pkl` was never
generated in the Python reference â€” it is created lazily on first lookup, which
never ran. The FAA CSV downloads 300K+ rows for no benefit; removed entirely.

**Files in this section:**
- `core/receiver/.../enrich/IcaoTypeNames.kt` â€” all ~80 `_TYPE_MAP` entries from Python `lookup.py`; pure Kotlin.
- `app/.../data/AircraftMetaEnrichment.kt` â€” suspend `lookup(icao)` with 30-day Room cache; negative caching; calls hexdb/OpenSky/adsbdb in priority order.
- `app/.../data/FlightAwareEnrichment.kt` â€” scrapes `trackpollBootstrap` JSON; 4s initial delay, 30s retry; callsign-upgrade invalidation.
- `AppDatabase.kt` v4 â†’ v5: FAA table dropped; `MIGRATION_4_5` added.
- `AppModule.kt`: `MIGRATION_4_5` wired in; `FaaAircraftDao` `@Provides` removed.
- `PipelineService.kt`: `faaDatabase` lazy and `@Inject faaAircraftDao` removed.

304 `:core:receiver` + 31 `:app` tests, 0 failures. `:app:assembleDebug` passes.

---

## 16. Phase 3.5 â€” Core receiver profiling and optimization (2026-07-26)

### Scope

Headless, JVM-only: `Demodulator` + `CrcChecker` + `MessageDecoder` + `AircraftManager`.
No UI, no Room, no enrichment â€” the pipeline kernel that runs on Android at full IQ rate.
Fixture: 256 K synthetic IQ bytes (seeded PRNG), matching the `BLOCK_SIZE = 262144`
block fed to `demodulate()` on each USB read. Measures the common steady-state case:
busy RF band with occasional preamble candidates, most rejected by `deltaFloor`.

### Benchmark harness

`core/receiver/src/test/.../demod/DemodulatorBenchmarkTest.kt` â€” two JUnit 5 tests:
1. **Demodulator only** â€” 300 iterations Ã— 256 K IQ block, 20-iteration JIT warmup.
2. **Full pipeline** â€” same iterations + CRC check + decode, same warmup.

Run with `./gradlew :core:receiver:test --tests "*Benchmark*"`.

### Hot-path allocation analysis (pre-optimization)

| Site | Allocation per call | Frequency |
|---|---|---|
| `computeMagnitude()` â†’ `IntArray(131072)` | 524 KB | every IQ block |
| `extractBits()` â†’ `IntArray(112)` bits | 448 B | every preamble candidate that passes `preambleOk` |
| `extractBits()` â†’ `IntArray(14)` bytes | 56 B | same |
| `detectFrames()` â†’ `full.copyOf(nBits/8)` | 28â€“56 B | every kept frame |
| `mergeMessage()` â†’ `signalHistory.toMutableList()` | varies | every message with signal |
| `mergeMessage()` â†’ `messageHistory.toMutableList()` | varies | every decoded message |

The `IntArray(131072)` dominates by a wide margin: at 4 IQ blocks/s (2 Msps, 131072
samples/block Ã— 2 bytes = 65.5 ms/block) that is ~2 MB/s of GC pressure from a
single allocation, collected at each block boundary. On Android, minor-GC pauses
are more disruptive than on a server JVM.

### Fixes applied

**1. Reuse the magnitude buffer** (`Demodulator.kt`):
- Added `private val magBuffer = IntArray(BLOCK_SIZE / 2)` as a member field.
- `demodulate(data: ByteArray)` now fills `magBuffer` in-place instead of calling
  `computeMagnitude()`, which always allocated. No API change â€” `computeMagnitude()`
  is kept unchanged for tests; `demodulate()` no longer calls it.
- `detectFrames(mag: IntArray, len: Int)` overload added, receiving the buffer length
  so the scan is bounded by actual data, not the full pre-allocated array.
  `detectFrames(mag: IntArray)` (original signature, used by tests) delegates.

**2. Reuse the bits buffer** (`Demodulator.kt`):
- Added `private val bitsBuffer = IntArray(MODES_LONG_MSG_BITS)` and
  `private val bytesBuffer = IntArray(MODES_LONG_MSG_BITS / 8)`.
- New private `extractBitsInto(m, start, nBits, bitsOut, bytesOut): Boolean` fills
  caller-supplied arrays and returns false on rejection â€” no allocation.
- `detectFrames` now calls `extractBitsInto` instead of `extractBits`. The only
  remaining allocation per kept frame is `bytesBuffer.copyOf(nBits / 8)` (14 ints),
  which goes directly into `RawFrame` â€” unavoidable.
- `extractBits()` (public, used by tests) is unchanged.

`AircraftManager.mergeMessage`'s `toMutableList()` pattern (signal/message history)
was identified as a secondary hotspot but left unchanged: `AircraftState` is
intentionally immutable (data class, `copy()`), and the toMutableList+add+toList
pattern mirrors Python's `deque.append()` semantic. Changing it would require either
mutable state (breaking the publish model) or structural copy anyway â€” the allocation
is per-message, not per-IQ-block, and the list sizes are bounded at 60 and 50 entries.

### Benchmark results

Machine: Windows 10, JVM 17. Both runs use identical fixture, 300 iterations, 20-iteration warmup.

| Stage | Baseline | Optimized | Change |
|---|---|---|---|
| Demodulator only | 0.72 ms/call, 365 MB/s | 0.65 ms/call, 406 MB/s | +11 % throughput |
| Full pipeline (demod + CRC + decode) | 0.65 ms/call, 401 MB/s | 0.65 ms/call, 404 MB/s | flat (decoder overhead dominates on 0-message fixture) |

At 4 IQ blocks/s (normal USB read rate at 2 Msps), the optimized pipeline processes
each 256 K block in ~0.65 ms, leaving >99 % of the block's 65.5 ms budget for
decoding, enrichment, and UI. The 524 KB-per-call allocation is eliminated â€” no more
minor-GC bursts at the IQ read boundary on the Android runtime.

### Parity harnesses

304 `:core:receiver` tests (up from 302 â€” 2 new benchmark tests), 0 failures.
Both golden parity suites (`GoldenFrameParityTests`, `GoldenStateParityTests`) pass
unaffected â€” the optimized code paths produce byte-identical output to the originals.

### Settings fix (this session, same deploy)

- `AppConfig.enrichmentEnabled` default changed `false` â†’ `true`. The setting
  existed but was off by default, silently gating all enrichment on a fresh install.
- Settings section renamed "Decoding and logging" â†’ "Data".
- Toggle relabeled "Route lookup" â†’ "Network enrichment" with updated description
  listing four sources (hexdb.io, OpenSky, adsbdb, FlightAware). FAA CSV removed 2026-07-27.

---

## 17. Phase 6 â€” Hardware validation partial results (2026-07-27)

Tests run on Pixel 6 + R828D dongle over WiFi ADB. 8-hour soak tests deferred; max
test time 1 hour per session.

### Tests completed this session

**Process death recovery.** `am force-stop` (root not available, `kill -9` not
permitted) â†’ cold restart â†’ app reconnected to USB dongle and resumed decoding within
3s. No data corruption, no crash loop, no ANR. `kill -9` would be the stronger test;
deferred until a rooted device or direct USB ADB is available.

**Memory baseline.** Heap snapshot `heap_t0.hprof` captured at `/data/local/tmp/`
during live decode. Mid-session comparison interrupted by WiFi ADB disconnect.
Full before/after comparison outstanding.

**Thermal.** `dumpsys thermalservice` shows no throttling events during a short decode
window. Direct sysfs thermal zone reads are permission-denied without root. Sustained
decode thermal data outstanding (30-min soak not yet completed).

**Offline mode.** `svc wifi disable` was used in an earlier attempt â€” it killed the
WiFi ADB transport, preventing completion. Correct approach (iptables or airplane mode
toggle via UI) not yet executed.

**Crash/ANR dropbox.** `dumpsys dropbox` â€” not yet retrieved this session.

### Outstanding Phase 6 tests

| Test | Status |
|---|---|
| 30-min sustained decode under load | âŒ not run |
| Memory soak (heap compare t0â†’t30) | âŒ t0 exists on device, t30 not captured |
| Offline mode (iptables or airplane mode) | âŒ not run |
| Crash/ANR dropbox scan | âŒ not run |
| Thermal under 30-min sustained decode | âŒ not run |
| `kill -9` process death (needs root or USB ADB) | âŒ blocked |

---

## 18. Airline name layout fix (2026-07-27)

**Bug.** In `AircraftRow.kt`, the `operator` (airline name) Text had
`Modifier.weight(1f)` in a `Row` alongside non-weight items: ICAO, callsign,
aircraft type ("Airbus A320neo"), registration ("N392FR*"), and ProvenanceMark. The
non-weight items consumed ~310 dp, squeezing the weighted item to ~1 char width and
forcing the airline name to render one character per line vertically.

**Fix.** Replaced `weight(1f)` on the operator Text with `Spacer(Modifier.weight(1f))`
to push type/reg to the right; moved `operator` to its own line below the row with
`maxLines = 1, overflow = Ellipsis`. Layout now: ICAO + callsign on the left, spacer,
type + registration + provenance mark on the right (top line); airline name on its own
line below, truncated if long.

Smart-cast across module boundary (`state.operator` is a public API property in
`:core:receiver`, Kotlin can't smart-cast it in `:app`) â€” fixed with `.let { operator
-> }` idiom.

Verified on-device: layout correct, no vertical cascade. Airline names will appear
once enrichment has had time to run (fires async, 4s after first seen).

---

## 19. USB disconnect detection and auto-reconnect (2026-07-27)

**Symptom.** After unplugging and replugging the dongle, no messages flowed. The
badge still read Running and no error appeared.

### Two root causes, both structural

**1. `NetworkSource` opened the IQ socket with `soTimeout = 0`.** When the driver
holds the socket open with no device behind it, `read()` blocks forever: no EOF,
no exception. `runIqLoop`'s error path never ran, `_sourceState` stayed `Running`,
and nothing ever concluded the session had died. The old comment â€”
`// no timeout - stream is continuous` â€” inverted the logic. *Because* the stream
is continuous (a 256 KB block every ~65 ms at 2 Msps), silence is diagnostic;
continuity is the reason a timeout is safe, not a reason to omit one.

This matters because rtl_tcp offers no other liveness signal. The `RTL0` greeting
is sent once per connection and never repeats; every command (`0x03` gain mode,
`0x04` gain, `0x0e` bias tee) is write-only with no reply, so `writeBytes()`
returning true means the local socket accepted bytes, not that a dongle exists.
Sample flow is the only evidence available.

**2. Recovery fired exactly once.** `runIqLoop`'s catch did one `restartPipeline()`
after a 2 s delay. If the dongle was not back by then, the receiver settled into
`Error` permanently regardless of what happened afterwards.

### Three failed attempts, and why

All three targeted detection while both causes above stayed intact:

1. Made `onAttached` unconditional â€” assumed the driver's attach broadcast fires.
2. Added `UsbReconnectGuard` to skip the stale-session fast path â€” assumed
   `onDetached` fires to arm it, while `UsbHotplugReceiver`'s own doc comment says
   detach is "not always fired".
3. Extracted that guard and unit-tested it. Five tests passed, proving a boolean
   flips. Nothing set the boolean. **Passing tests made an unverified fix look
   verified** â€” the worst of the three.

### What landed

| Change | File |
|---|---|
| Finite IQ read timeout (`IQ_READ_TIMEOUT_MS = 3000`, ~45x the healthy 65 ms block interval) | `NetworkSource.kt`, `RtlSdrDefaults.kt` |
| `tryConnectExisting` requires real sample flow, not just the replayed greeting | `UsbRtlSdrSource.kt` |
| Retry loop with capped exponential backoff replaces the fire-once restart | `PipelineService.kt` |
| USB presence via `UsbManager.getDeviceList()` gates each retry | `UsbPresence.kt` (new) |
| System `ACTION_USB_DEVICE_DETACHED` added alongside the driver's broadcasts | `UsbHotplugReceiver.kt` |
| Backoff schedule as pure logic + 6 tests | `ReconnectPolicy.kt` (new) |
| `UsbReconnectGuard` + its 5 tests deleted â€” obsolete once staleness is measured | â€” |

**Why the presence gate is load-bearing, not an optimisation.** Opening the source
launches `SdrSourceActivity`, a trampoline Activity. Firing that on a timer with no
dongle attached would flash over the foreground app and hit Android's
background-activity-launch restrictions. Polling the device list is also the only
reliable way to notice the dongle returned: `ACTION_USB_DEVICE_ATTACHED` is
delivered through the manifest device-filter, not to runtime receivers.

The manifest already declared `android.hardware.usb.host`, and there were zero
`UsbManager` references in the source â€” the authoritative API was available and
unused the whole time, while the app inferred USB state from a third-party app's
implicit broadcasts.

Both hotplug callbacks are now accelerators rather than the recovery mechanism;
the retry loop reconnects on its own schedule whether or not they fire.

### Verification status

310 `:core:receiver` + 31 `:app` tests, 0 failures; `:app:assembleDebug` passes;
deployed to the Pixel 6.

**Confirmed on hardware (2026-07-27).** Avi disconnected and reconnected the
dongle on the Pixel 6; the receiver recovered. This is the acceptance criterion
that mattered â€” the unit tests cover the backoff schedule only, not whether
reconnection actually works, and three earlier fixes had passed their own tests
while doing nothing on a real device.

Still unconfirmed, and no longer blocking: whether the driver's
`com.sdrtouch.rtlsdr.*` broadcasts fire at all, and whether
`RECEIVER_NOT_EXPORTED` is what blocks them. Moot for correctness now â€” both
hotplug callbacks are accelerators, not the recovery path â€” but would explain why
they were unreliable if anyone wants to chase it later.

---

## 20. Unified error message, dismissible Live banner, gain-mode restart, UI fixes (2026-07-27)

**1. Single short error message.** Every USB failure path (`SdrDriverFailedException`,
timeout, generic exception, IQ stream EOF, hotplug detach) now sets
`SourceState.Error(PipelineService.NO_DONGLE_MESSAGE)` â€” "no dongle found or
critical error - check and reconnect the dongle to continue" â€” instead of each
catch block building its own multi-sentence string. The old detailed text still
goes to `ErrorLog` via `describeError()`/exception messages, so diagnosis
information isn't lost, only removed from the user-facing string.

**2. Dismissible error banner on Live.** Previously the error text only appeared
inside the empty-aircraft placeholder, so it disappeared the moment any aircraft
remained in the list. `TextListScreen` now shows an `ErrorBanner` (a dismissible
`Surface` row with a close icon) above the list whenever `sourceState is
SourceState.Error`, regardless of whether the aircraft list is empty. Dismissal
state is `remember(sourceState)`-scoped: since `PipelineService` emits a new
`Error` instance on every failed reconnect attempt, dismissing one banner does not
suppress the next one.

**3. Message-history timestamp wrap.** `MessageRow`'s timestamp `Text` had no
`maxLines`/`softWrap`, so at some font-scale settings the last digit of
`HH:mm:ss` wrapped onto a second line inside its 60dp column. Fixed with
`maxLines = 1, softWrap = false` and widened the column to 68dp.

**4. Back button from aircraft detail.** No `NavHost` exists â€” screen selection is
a set of booleans in `MainActivity`. The app-bar back arrow already cleared
`detailIcao`, but the system/gesture back button had no handler and fell through
to the Activity default (minimize). Added `BackHandler(enabled = detailState !=
null) { detailIcao = null }` pointed at the same action.

**5. Auto-gain mode switch now restarts the pipeline.** `ConfigChange` previously
routed every gain change â€” including autoâ†”manual â€” through the live rtl_tcp
control channel (`requiresGainReapply`). By decision, switching gain *mode* now
triggers `requiresPipelineRestart` instead; only picking a different level within
the same mode still goes out live. `requiresGainReapply` narrowed accordingly
(`old.autoGain == new.autoGain && old.gainTenths != new.gainTenths`) so the two
paths can't double-fire â€” `PipelineService.updateConfig` already checks restart
first and returns. `CLAUDE.md`'s "ppmCorrection is the only setting that restarts
the pipeline" line updated to match; it was the reason this was previously live-only.

**Tests.** `ConfigChangeTests` rewritten for the new restart/reapply split
(mode-switch restarts, same-mode level change reapplies live). 310
`:core:receiver` + 32 `:app` tests (up from 31), 0 failures. `:app:assembleDebug`
succeeds, deployed to the Pixel 6.

**Verified on-device (2026-07-27, Avi):** banner dismiss, timestamp column, back
button, and the auto-gain restart all confirmed working on the Pixel 6.

---

## 21. Squawk display confirmed correct on live DF5/DF21 traffic (2026-07-27)

Avi observed live squawk codes rendering as 4 digits, not 5 â€” confirming
`decodeIdentity()` (Â§ "Squawk decode" in `MessageDecoder.kt`) works correctly on
real hardware, not just against Python vectors. Traced for this note: `a`, `b`,
`c` are each built from 3 bits (range 0â€“7) and `d` from 2 bits doubled (0/2/4/6),
so every digit is a single octal character by construction â€” `"$a$b$c$d"` cannot
produce a 5-character result. The 5-digit failure mode this guards against is the
one the doc comment already names: the previous dump1090-derived version rendered
a packed hex value as octal, turning a legitimate code like 6272 into "61162".
No code change; closes the "no live DF5/DF21 traffic observed" defect.

---

## 22. DF5 / DF16 / DF21 seen on air â€” detection confirmed, decode not (2026-07-27)

Avi observed DF5, DF16 and DF21 frames being detected by the running receiver,
explicitly noting he could not say whether they were decoded *correctly*. Recorded
at that precision deliberately: detection and correctness are separate claims, and
three fixes earlier in this session looked verified while being wrong.

**What detection does establish.** These frames pass the CRC gate and reach their
decoders. DF5/DF21 are parity-address formats with no recoverable ICAO of their
own, so they only get through via `CrcChecker`'s RECOVERED path â€” matching against
an ICAO already confirmed by a CRC-valid frame. Their arrival therefore also
exercises `IcaoCache`, not just the format dispatch. For DF16, `decodeDF16` always
computes `tcasSl` (3 bits, masked `0x07`), `altitudeFt` via `decodeAc13Field`, and
`onGround = false`.

**What it does not establish.**

- **No value is externally cross-checked.** Nothing compares a decoded squawk,
  SL or DF16 altitude against ground truth. Structural guarantees only rule out
  malformed output (Â§21: squawk cannot exceed 4 octal digits; `tcasSl` cannot
  leave 0â€“7 given the mask) â€” they say nothing about whether the *right* bits
  were read.
- **The TCAS RA path is untouched.** `decodeRaMv` runs only when the MV field's
  first byte is `0x30` (BDS 3,0). Routine air-air surveillance carries a
  different BDS code, so ordinary DF16 traffic never enters RA decode. Every RA
  field â€” `tcasRaActive`, `tcasRaText`, `tcasRaComplement`, `tcasRaTerminated` â€”
  remains verified against synthetic vectors and the Python reference only.
- **`tcasTargetIcao` stays null regardless**, as proven in Â§10; DF16 traffic on
  air does not change that.

**Cheapest real validation, if wanted later.** DF4/DF16 altitude and DF17 TC9-18
altitude for the same ICAO within a second or two should agree â€” the two use
independent decode paths (`decodeAc13Field` vs the Gillham/Q-bit path), so
agreement is genuine evidence both are right. For squawk, the only external truth
is comparing an observed code against the same aircraft on a public tracker.
Neither is built.

**Status: accepted as working by Avi's decision, 2026-07-27.** These paths are
treated as good and are no longer tracked as defects. Recorded here rather than
silently upgraded to "verified" because the distinction still matters if a symptom
ever appears: the acceptance rests on the frames decoding without anomaly, not on
any value having been cross-checked. If a squawk or a DF4/DF16 altitude ever looks
wrong on air, this is the first place to look and the cross-check above is the
first thing to build.


---

## 23. Offline maps — Wi-Fi-only download and segment management (2026-07-29)

Full feature per spec: radius downloads, named segments, travel-based append,
explicit deletion with shared-content retention. 90 tests (72 core + 18 app).

### Module split

Every rule is pure Kotlin in `:core:receiver`; Android appears only in adapters.
That split is what makes the hard cases testable — Wi-Fi loss mid-download, torn
manifest writes and shared-tile deletion are all ordinary function calls against
fakes rather than device conditions nobody can reproduce.

| Core (`offline/`) | App (`offline/`, `ui/offline/`) |
|---|---|
| `OfflineRadius`, `MapDetail` | `AndroidNetworkEligibility` |
| `TileGeometry`, `TileRef`, `GeoBounds` | `AndroidLocationNamer` (Geocoder) |
| `OfflineSegment`, `OfflineManifest` | `FileTileStore`, `FileManifestStore` |
| `NetworkEligibility`, `OfflineDownloadPolicy` | `OsmTileDownloader`, `LocalOnlyTileDownloader` |
| `SegmentNaming`, `TravelTracker`, `AppendTargeting` | `LogcatOfflineLogger` |
| `OfflineMapManager` | `OfflineMapsScreen`, `OfflineMapsViewModel` |

### Decisions worth recording

**Metered Wi-Fi is refused.** A tethered hotspot is a cellular plan wearing a Wi-Fi
transport; matching on transport alone would bill the user for a 250 MB download.
`UNKNOWN` is likewise treated as ineligible — an unclassifiable transport is never
assumed safe.

**Eligibility is re-read per batch (20 tiles), not once per download.** Losing Wi-Fi
stops the run within at most one batch instead of continuing on whatever the device
fell back to.

**Offline mode reads the network once per gate.** An early version called
`currentState()` twice — once to decide, once to log — which straddled network
changes and made the log record a state the decision was never based on. Caught by a
test that expected a pause and got an immediate rejection.

**Coverage is a disc, not the enclosing box.** The corners of a bounding square sit
up to 41 % beyond the requested radius — roughly a fifth of the tiles, downloaded for
range the user never asked for.

**Appends add a `CoverageEntry`; they never rewrite the original.** An append cannot
shrink or invalidate the download it was added to.

**Reference counting is per segment, not per entry.** Two appends covering the same
area inside one segment must count once, or deleting that segment would leave tiles
orphaned with a positive count.

**Deletion writes the manifest before removing tiles.** If the process dies between
the two, tiles are orphaned (recoverable via `pruneOrphanedTiles`) rather than a
segment pointing at content that is gone, which reads as corruption to the renderer.

**Offline tiles live outside osmdroid's cache.** osmdroid trims to 500 MB by age;
sharing that directory would let ordinary map browsing evict a deliberately
downloaded region.

**`FileManifestStore` takes an `OfflineLogger`, not `ErrorLog`.** The latter calls
`android.util.Log`, which would force Robolectric on a class whose entire job is file
I/O that works fine on a plain JVM. Found by a failing test, not by review.

### Tile provider: now defaults to OSM (2026-08-01 update)

`AppModule` binds `ConfigurableTileDownloader`, reading `AppConfig.offlineTileUrlTemplate`
per fetch. The map renders from `tile.openstreetmap.org`, whose usage policy explicitly
prohibits this feature's shape — it names "pre-seeding large areas or multiple zoom
levels" and "Download region for offline use" as blockable, and states offline use is
not permitted on their servers.

Originally this template had **no default** for exactly that reason (see the v1.0
design note this replaces, below) — enabling downloads was meant to require a
conscious, typed-in choice. As of the "OSM default tile source" change,
`AppConfig.offlineTileUrlTemplate` defaults to `tile.openstreetmap.org`'s own URL, so
flipping Settings → "Enable offline map downloads" alone is enough to start bulk
downloads against it. Raised during the 2026-08-01 UI audit; kept as-is by explicit
decision — the ToS risk is accepted, not accidental. Options and sizing are in
`docs/OFFLINE_MAPS.md`.

### Made usable: cache import (2026-07-29, same session)

The download path needed a tile endpoint nobody had chosen, so the feature shipped
inert. Fixed by adding the path that needs no endpoint at all.

**`OsmdroidCacheSource`** reads osmdroid''s own `cache.db` and adopts already-viewed
tiles into a managed segment. This is the primary path and works today:

- No network, no provider, no Wi-Fi requirement — the bytes are already on the phone,
  so gating it would block an operation that cannot cost anything. `LocalTileSource`
  exists to make that distinction explicit rather than implicit in a boolean.
- Solves a real problem beyond licensing: osmdroid trims its cache to 500 MB **by
  age**, so deliberately studied areas evict themselves. Importing moves them where
  nothing is deleted without the user asking.
- Tile keys come from osmdroid''s own `MapTileIndex`, not a hardcoded
  `(z << 58) + (x << 29) + y`. A copied formula would break silently on a library
  upgrade, because a wrong key reads as "not cached" rather than as an error.

**`ConfigurableTileDownloader`** resolves `AppConfig.offlineTileUrlTemplate` per
fetch. Empty (the default) falls back to local-only; set, downloads go there. Read
per fetch rather than captured at injection so a Settings change applies without a
restart. Templates missing `{z}`/`{x}`/`{y}` are rejected at entry — one would
request the same tile forever and store a single image as a whole region.

**Travel tracking wired up** — `PipelineService.onGpsFix` calls `observePosition` on
the IO dispatcher. Records only while Follow GPS is active; writes a note, never
fetches.

391 core + 55 app tests, 0 failures. 81 offline-specific in core (9 new for import)
plus 8 file-storage integration tests.

### Still not done

- **Ambiguous append targets have no picker UI.** The manager returns
  `AppendTarget.AmbiguousChoice` with candidates and the button disables; the
  disambiguation dialog is not built.
- **No background/`WorkManager` integration.** Downloads stop with the screen, so a
  pending Wi-Fi intent is not persisted as a task. Travel notes *are* persisted.
- **No storage ceiling.** Nothing is auto-deleted, by requirement, so a user can fill
  the device. Usage is shown; enforcement would mean automatic deletion.
- **Not exercised on-device.** ADB was unreachable at deploy time across several
  attempts; the whole offline feature is unverified on hardware.

## 24. CRC two-bit correction + StatusStrip status-bar overlap fix (2026-08-02, v1.2.0)

**CRC two-bit error correction added to `CrcChecker`.** After single-bit correction
fails, DF17/18 frames now also try flipping every pair of the 88 data bits
(`correctTwoBits`/`buildTwoBitSyndromes`, C(88,2) = 3,828 candidate pairs) and accept
the fix only if the resulting syndrome maps to exactly one pair — ambiguous syndromes
are rejected rather than guessed, same policy as the existing single-bit path.

**Deliberate divergence from the Python reference.** `checker.py` implements only
single-bit correction; two-bit is new. False-accept risk is higher than single-bit's
(documented in the class doc comment) — roughly 3,828 candidate pairs vs. 88 single-bit
positions, so a coincidental accept is ~44x likelier per corrupted frame. Covered by
4 new tests in `CrcParityTests` (two-bit correction, disabled via `correctSingleBit`,
CRC-bit immunity, signal-level survival through `.copy()`).

**StatusStrip (uptime/msg-rate/CRC%) was rendering under the phone's system status bar
icons**, overlapping the clock/battery/signal glyphs. The app has no edge-to-edge
inset handling anywhere (`MainActivity.kt` never called `enableEdgeToEdge()` or
consumed `WindowInsets`), so once the OS started drawing the app edge-to-edge the top
`Column` in `AdsbScaffold` (StatusStrip + NavHost) had nothing pushing it below the
status bar. Fixed with `Modifier.statusBarsPadding()` on that Column — a no-op if the
window is ever non-edge-to-edge, so it's safe regardless of OS/theme behavior. No
other UI changed.

**Version bumped v1.1.0 → v1.2.0** (`versionCode` 10 → 11) for this change.

**Two-bit correction is independently switchable.** `AppConfig.crcCorrectTwoBit`
(default `false`, persisted via `AppConfigStore`) gates it separately from
`crcCorrectSingleBit` — either, both, or neither can be on, so single-bit can be
turned off entirely while two-bit runs alone, or vice versa. Settings → Data has a
second switch below "Single-bit CRC correction". Both flags are read live per frame
in `PipelineService`, same as the existing single-bit switch — no pipeline restart.
Added so the two can be A/B compared over time before settling on a default.

Full suite: `./gradlew.bat :core:receiver:test :app:testDebugUnitTest :app:assembleDebug`
— 396 core + 57 app tests, 0 failures; debug APK builds. Not run on-device (see rule
in [CLAUDE.md](../CLAUDE.md) memory: UI changes are verified by Avi on the physical
phone, not by screenshot/simulated-tap tooling here).

## 25. Live row redesign (4 lines) + History CSV export/share (2026-08-02, v1.3.0)

**`AircraftRow` restructured from 3 lines to 4**, by decision after reviewing mockups:
- Line 1: ICAO + [RA badge] (previously also carried callsign/type)
- Line 2: callsign + tail number (registration) — new grouping
- Line 3: type code + airline (operator) + route — type code moved down from line 1
- Line 4: signal bars · msgs · speed · age — unchanged from the old line 3

Right-hand DIST/ALT/TRACK columns untouched, per explicit instruction. Row height
grows beyond the 72 dp floor (already documented as a floor, not a cap) — fewer
aircraft fit on screen without scrolling, a known and accepted trade-off.

**Line 2 can be genuinely empty** (no callsign and no registration), unlike line 3
which always has a "route not found" fallback. Fixed with an invisible placeholder
`Text(" ", ..., color = Color.Transparent)` in the callsign's own style, so that
line reserves its height instead of collapsing and making the row shorter than its
neighbours in the list.

**Test regression found and fixed, not in production code.** `AircraftRowLayoutTests`
started failing after the redesign — three stacked 4-line rows are tall enough that
Robolectric's compose-test root (a bounded virtual screen height) clamps the last
row's last line instead of growing to fit, coercing its measured height down. The
real `LiveScreen` never hits this because it's a `LazyColumn`, which doesn't
height-constrain off-screen content the same way. Fixed by wrapping the test's
`Column` in `verticalScroll(rememberScrollState())` so it measures at natural height
regardless of the harness's viewport — matches how the production list behaves.
Confirmed via a throwaway diagnostic test (added, used to isolate the failing line,
then deleted) rather than guessing from source reading alone.

**History → CSV export + share.** `CsvExporter.exportAircraftSeen()` writes the full
`aircraft_seen` table (icao, callsign, registration, aircraft_type, operator, route,
altitude, ground speed, track, lat/lon, distance, squawk, message count, first/last
seen) to a CSV under `getExternalFilesDir`. Timestamps are `MM/dd/yyyy HH:mm` — no
seconds, per explicit instruction. `PipelineService.exportHistoryCsv()` runs it on
`Dispatchers.IO`; a new "Share" button next to "Clear" on the History screen triggers
it and opens the system share sheet via a `FileProvider` (`res/xml/file_paths.xml`,
manifest `<provider>` entry — new, this app had none before). CSV fields are quoted
only when they contain a comma/quote/newline, to keep normal fields readable.

Full suite: 396 core + 57 app tests, 0 failures; debug APK builds. Deployed to the
Samsung SM-S928B test device. Not visually verified here — Avi checks the Live
screen and History → Share on-device (per the no-screenshot-debugging memory).

## 26. Live row split into top strip + full-width lines (2026-08-03, v1.4.0)

After reviewing §25's 4-line row on-device, the type code turned out to be a
verbose full name (`Boeing 737 MAX 9`, not `B738`), crowding line 3 and starving
the route down to 2-3 truncated characters. Fixed by reordering and widening,
confirmed against a mockup with realistic long names before coding:

- Line 1: ICAO + [RA badge], line 2: callsign + tail number — unchanged, still
  share the row with the DIST/ALT/TRACK data block exactly as before.
- Line 3 (airline + route) and line 4 (type code) moved **out of that shared row**
  into their own full-width rows below it — the data block is only two lines tall,
  so nothing sits to their right once you're past it; they now run edge to edge
  instead of stopping where DIST starts.
- Line 5 (signal/msgs/speed/age) stays at the old narrower width — reserved via
  `Modifier.padding(end = DataBlockWidth + RowGutter)` rather than sharing a Row
  with the data block like lines 1-2 do.
- `AircraftRow`'s outer container changed from a `Row` to a `Column` to host this
  split; `AircraftRowLayoutTests`'s "gutter"/height checks still pass unchanged
  since `IDENTITY` now tags only the top-strip's lines-1-2 Column, which is exactly
  what those checks are about.

## 27. Aircraft Stats screen — log book of every tail/ICAO ever seen (2026-08-03, v1.5.0)

New, independent of History. `aircraft_seen` (History's table) replaces its row on
every re-sighting and is wiped by History's Clear — neither survives what this
needed: a permanent "how many times have I seen this aircraft" count.

**`aircraft_visits`** (new table, `MIGRATION_5_6`, DB v5→v6): one `@Insert` row per
departure, written alongside the existing `aircraft_seen` upsert in
`PipelineService.recordDeparted()`. Never replaced, never touched by Clear.

**Airline vs private, decided by Avi:** `isAirline = (operatorSource == DataSource.ALGORITHMIC)`
— true only when the operator name came from `Airlines.fromCallsign` (the offline
callsign-prefix table, set in exactly one place, `OfflineEnrichment.enrich`).
Everything else — hexdb.io owner names, FlightAware-scraped airline names alike —
counts as private. This was an explicit tradeoff Avi chose over a rule that would
also require a new field: some legitimately-commercial flights whose airline name
only resolved via network scrape land in the "private" tab. No new field was needed
because `AircraftState.operatorSource` already carried exactly this signal.

**Aggregation is plain Kotlin, not SQL** — `ui/stats/AircraftStats.kt`'s
`summarizeVisits()` groups the full visit list by ICAO (identity fields taken from
each aircraft's most recent visit, counts/min/max computed across all of them),
mirroring `HistoryScreen.kt`'s own in-memory filter/sort/group pattern rather than
writing `GROUP BY` queries. Pure function, unit-tested with plain JUnit
(`AircraftStatsTests.kt`), no Robolectric needed.

**`ui/stats/StatsScreen.kt`** — originally reached via Settings → "Aircraft stats"
(mirrored the "Manage offline maps" section/route pattern, not a 6th bottom-nav
tab). **Superseded by §33 (2026-08-05):** now a peer sub-tab inside Traffic
instead, with its own Scaffold/TopAppBar/back-arrow stripped. Two tabs, "By
airline" (grouped, sticky header per airline
showing aircraft count + total sightings) and "Private aircraft" (flat, sorted by
times-seen). Tapping a row opens a `ModalBottomSheet` listing every visit's date
(`MM/dd/yyyy HH:mm`, matching the CSV-export format from §25) and duration (reuses
`HistoryScreen.kt`'s `formatDuration` — `internal`, so module-visible, no duplicate).

Full suite: 396 core + 64 app tests (7 new: 6 `AircraftStatsTests` + 1 migration
test), 0 failures; debug APK builds. Deployed to the Samsung SM-S928B. Migration
confirmed against a real SQLite engine in `AppDatabaseMigrationTests` (existing
`aircraft_seen` rows survive v5→v6 untouched, new `aircraft_visits` table is
immediately usable) — not yet confirmed against the actual on-device database file,
which has accumulated real migrations v1 through v5 already.

## 28. Coverage history, best range ever, first-time-seen alerts, base map picker, version display (2026-08-03, v1.6.0)

Follow-up to the feature brainstorm against FlightRadar24/ADS-B Exchange/PlaneFinder
— features that lean into what a *personal* receiver can show that a crowd-network
app structurally can't, plus a base-map option and a housekeeping ask (show the
app version somewhere — it was nowhere in the UI before this).

**Coverage history (`coverage_samples` table, DB v6→v7).** `CoverageMetrics.computeRow()`
already produced an 8-sector row every 5 minutes, but it only ever reached a
write-only CSV (`CoverageCsvLogger`) or a live 10s-refreshed `StateFlow` — nothing
persisted it across restarts. Now every non-empty sector from each 5-minute tick is
also written to `coverage_samples`. New pure function
`CoverageMetrics.synthesizeAllTimeRow()` (`core/receiver`) aggregates the full
history back into the same `CoverageMetricsRow` shape the live view already uses —
**deliberate simplification**: only count/max survive the persisted aggregation, so
median/p90 collapse to max in the all-time view (documented in the function's doc
comment, unit-tested). `ReceiverScreen.kt`'s Coverage card gets a LIVE/ALL-TIME
toggle feeding the *same* `CoveragePolar` composable — no new chart code.

**Best range ever (`best_range_record`, singleton row).** Checked on the same
5-minute tick (not per-message — a personal best doesn't need per-fix precision,
and this avoids a DB round-trip on every decoded position), scanning the live
`AircraftState` list for the longest `distanceNm`. Comparison extracted as a pure
`isNewBestRange()` (`pipeline/BestRange.kt`), unit-tested. Shown as a line in the
Coverage card: distance, callsign/ICAO, and when.

**First-time-seen milestone notification.** `AircraftVisitDao.countByIcao()` checked
in `recordDeparted()` *before* inserting the new visit row — zero means this ICAO
has never departed before. Posts to a **new** notification channel (`adsb_milestones`,
`IMPORTANCE_DEFAULT`) — deliberately separate from the existing ongoing foreground
channel (`adsb_pipeline`, `IMPORTANCE_LOW`, non-dismissible by design), since
milestones need to actually alert. Scope is deliberately just "first time this ICAO
ever" — not also "first time this aircraft type," flagged as a fast-follow, not
built now.

**Base map picker — OSM + Esri, Google explicitly out of scope (Avi's decision).**
Google Maps tiles can't be pulled through a generic raster tile URL — their ToS
requires the actual Maps SDK, a separate widget with its own overlay API, needing
its own API key and every existing overlay (range rings, markers, trails,
clustering) re-implemented a second time. Not built. OSM and Esri World Imagery
*are* simple `{z}/{x}/{y}`-style (Esri: `{z}/{y}/{x}`) raster services — new
`BaseMap` enum (`core/receiver/.../map/BaseMap.kt`, pure, no Android deps), new
`AppConfig.mapBaseMap` field persisted like every other setting, `MapScreen.kt`
builds a custom `OnlineTileSourceBase` from the selected template (osmdroid's own
`XYZTileSource` string-concatenates `z/x/y` and can't fit Esri's swapped order).
**The dark invert filter is now conditional** — it was unconditionally applied to
every tile source before this; running it on Esri's satellite photography would
invert real colors into nonsense, so it's OSM-only now. Settings → Base map shows
each option's required attribution as its row subtitle.

**Version display.** `buildConfig = true` added to `app/build.gradle.kts`
(AGP 8+ requires explicit opt-in); Settings gets a new About section reading
`BuildConfig.VERSION_NAME`/`VERSION_CODE`.

Full suite: 400 core + 69 app tests (9 new: 4 `CoverageMetricsTests.SynthesizeAllTimeRow`
+ 4 `BestRangeTests` + 1 migration test), 0 failures; debug APK builds.

## 29. Base-map dark filter was leaking onto Esri imagery (2026-08-03, v1.6.1)

**Bug found via Avi's own testing.** Both OSM and Esri satellite imagery rendered
identically dark/grayscale-inverted, even though `MapScreen.kt` only intended the
invert-and-desaturate `darkTileFilter()` for OSM (`if (baseMap == BaseMap.OSM)
darkTileFilter() else null`). Root cause: passing `null` to
`TilesOverlay.setColorFilter()` to *clear* a previously-set filter isn't reliable
— osmdroid's `TilesOverlay` appears to treat a `null` argument as "no explicit
override" rather than "definitely clear the existing one," so once the invert
filter was set (e.g. from an initial OSM render, or a prior selection), switching
to Esri never actually removed it.

**Fix:** never pass `null`. Added `identityTileFilter` (a `ColorMatrixColorFilter`
built from an identity `ColorMatrix` — a real, distinct filter object that changes
nothing) as the explicit "off" state, applied via a new `tileFilterFor(baseMap)`
helper used at both the initial `MapView` setup and the `LaunchedEffect(config.mapBaseMap)`
live-switch. This guarantees the library always receives a genuinely different,
non-null filter object on every base-map change, rather than relying on `null`
being handled as "clear."

Not yet confirmed on-device — diagnosed and fixed from Avi's description (Esri
"grayscale/inverted, same as OSM") plus reading fetched OSM/Esri sample tiles
directly (both render normally/colorfully outside the app, confirming the bug was
in the app's filter application, not the tile sources themselves).

**Process note:** the v1.6.1 release APK was built *before* the version bump
landed in `build.gradle.kts`, so the shipped binary had the fix but reported
"1.6.0 (15)" in Settings → About. Caught by Avi after install. Going forward:
bump the version first, build second, always — never the reverse.

## 30. GPS-on-start fix + Esri place labels (2026-08-03, v1.6.2)

**GPS root cause found.** `PipelineService.refreshGpsCoordinates()` already ran
at every app start "independent of Fixed/Follow-GPS mode" (per its own doc
comment, from an earlier session) — but it silently no-ops without
`ACCESS_FINE_LOCATION`. That permission was only ever requested from Settings →
Observer position → "Follow GPS" (`SettingsScreen.kt`'s `onClick` on that
`OptionRow`). A user who stayed on the default **Fixed** mode — never touching
that toggle — never saw the permission prompt at all, so the "fresh GPS every
launch" behavior silently failed forever, regardless of mode. This is why the
map kept centering on the leftover Southern-California default coordinates for
an operator in Canada.

**Fix:** `MainActivity.kt`'s `AdsbScaffold` now requests `ACCESS_FINE_LOCATION`
unconditionally in a `LaunchedEffect(Unit)` — fires once per app start,
independent of any Settings interaction. Re-requesting an already-granted
permission is a documented no-op (callback fires immediately, no dialog), so
this is safe on every launch. The grant callback also calls `onUpdateGps {}`
directly, so the very launch that first grants permission still gets its fix
immediately rather than waiting for the next restart.

**Esri place labels.** Confirmed the root complaint ("no cities, streets") was
inherent to Esri's World Imagery layer — pure satellite photography, genuinely
no label data. Fixed by adding `BaseMap.labelUrlTemplate` (Esri's
`Reference/World_Boundaries_and_Places` service, a transparent-background tile
layer of place names/boundaries) and rendering it as a second `TilesOverlay` in
`MapScreen.kt`, inserted at index 0 of `mapView.overlays` so it always sits
above the base imagery but below the aircraft markers. Verified the reference
tile service actually returns labeled transparent PNGs before wiring it in, not
assumed. OSM is unaffected — its own tiles already carry labels, so
`labelUrlTemplate` is null for it.

Full suite: 400 core + 69 app tests, 0 failures; debug APK builds. Version
bumped to v1.6.2 *before* this build, per the process note above.

## 31. Meta-enrichment negative-cache TTL: 30 days → 2 hours (2026-08-04, v1.6.3)

Direct follow-up to §30's diagnosis, confirmed live against ICAO **C05737**
(Canadian Harbour Air DHC-3 Turbo Otter, registration **C-GHAS**) — real
evidence, not a hypothetical: at diagnosis time, `hexdb.io` was returning 504
and OpenSky's metadata endpoint turned out to be permanently retired (410
Gone, dead for every non-US lookup, not just this one), so the very first
lookup for this aircraft got all three sources fail and cached a "none"
result. **adsbdb had (and has) the full record the whole time** — verified
live: `registration: "C-GHAS", type: "DHC-3 Otter (Turbo)", operator: "Harbour
Air"`. Under the old 30-day TTL, that negative result would have blocked any
retry for a month regardless of the data being available.

**Fix:** `AircraftMetaEnrichment.kt`'s `CACHE_TTL_MS` changed from
`30 * 24 * 3600 * 1000L` to `2 * 3600 * 1000L`. The inline freshness check was
extracted into a standalone `isCacheFresh(cachedAtMs, nowMs, ttlMs)` function
(`internal`, file-scoped, no dependency on the class's DAO/HTTP client) so it's
directly unit-testable without mocking network or Room.

**Tests** (`AircraftMetaEnrichmentTests.kt`, new) use C05737's own timeline as
the scenario: a lookup cached 3 hours ago is stale under the new 2h rule and
must retry (matching what actually happened to this aircraft), while the same
3-hour-old entry is shown to have incorrectly stayed "fresh" under the old
30-day constant — a direct regression test for the exact bug found.

**Known related issue, not fixed here** (out of scope for this change,
flagged for a future pass if wanted): OpenSky's aircraft-metadata endpoint
being permanently gone (410) means `lookupIntl()`'s middle step now always
fails for every non-US ICAO — it still gets attempted (and its request
timeout eaten) before falling through to adsbdb, on every non-cached lookup.

Full suite: 400 core + 73 app tests (4 new `AircraftMetaEnrichmentTests`), 0
failures; debug APK builds. Version bumped to v1.6.3 before this build.

## 32. FA enrichment: drop the airborne-only filter, add an ICAO-hex ident fallback (2026-08-04, v1.6.4)

Two explicit follow-ups to §31's diagnosis:

**1. `FlightAwareEnrichment`'s `parse()` no longer requires `flightStatus ==
"airborne"`.** It previously discarded any flight whose FlightAware status
wasn't exactly "airborne" — for a short hop (a 20-30 min seaplane leg is the
real case that surfaced this) most retry attempts (every 30s, per
`FA_RETRY_INTERVAL`) land while the flight shows "arrived" or hasn't departed
yet, and got silently thrown away even though FlightAware genuinely had the
data. Now any entry with a non-blank `flightStatus` counts — still excludes
FlightAware's `"unknown": true` not-found placeholder (which has no
`flightStatus` field at all), so an unresolvable ident still correctly returns
`null` rather than a false empty-but-cached "result" that would wrongly stop
future retries.

**2. `PipelineService.maybeEnrichFa()` now falls back to the bare ICAO hex**
when neither callsign nor registration is available, instead of skipping the
FlightAware lookup entirely. Mostly matters for non-US aircraft — a US
aircraft already has its registration for free from the offline algorithm
(`Registration.fromIcao`), so this case was effectively non-US-only in
practice already; made explicit rather than silently relying on that
coincidence. **Verified live that this specific fallback does not resolve
real data for C05737** (`flightaware.com/live/flight/C05737` → "FlightAware
couldn't find flight tracking data... just yet") — FlightAware doesn't index
by raw Mode S address. Kept anyway since it may work for other aircraft, costs
nothing beyond the existing 30s retry throttle, and removes an artificial gap
where an aircraft with only an ICAO got zero enrichment attempts from any
source.

**Refactor for testability:** `parse()`, `titleCase()`, `cleanAirlineName()`
moved from private instance methods to top-level functions in the same file
(`parse` marked `internal`) — none touched instance state, so this makes
`parse()` callable directly from a test with just an HTML string, no
`FlightAwareEnrichment` instance or `CoroutineScope` needed. Also dropped a
`Log.d` call on the "not found" path once tests exercised it: `android.util.Log`
isn't available in a plain JVM unit test (no Robolectric here), and the debug
log wasn't worth pulling in Robolectric for.

**Tests** (`FlightAwareEnrichmentTests.kt`, new, 5 tests) use CGHAS/C05737's
real page structure as fixtures: an "airborne" flight still parses, an
"arrived" flight now parses too (the actual fix), FlightAware's "unknown"
not-found placeholder correctly yields no result, and a real flight is found
even when an unknown placeholder precedes it in the flights map.

Full suite: 400 core + 78 app tests (5 new `FlightAwareEnrichmentTests`), 0
failures; debug APK builds. Version bumped to v1.6.4 before this build.

## 33. "Live" nav destination renamed to "Traffic"; History and Stats moved in as sub-tabs (2026-08-05, v1.6.5)

Navigation restructuring, mockup-first per Avi's instruction (built with
`mcp__visualize__show_widget`, confirmed via `AskUserQuestion` before any code
changed). No content redesign — Live, History, and Stats each render exactly
as before; only where they live in the nav changed.

**`AdsbDestination.LIVE`** — label changed `"Live"` → `"Traffic"`. Route
string (`"live"`) and enum name (`LIVE`) untouched; only the bottom-nav label
changed.

**`ui/text/TrafficScreen.kt`** (new) — hosts a `TabRow` (Live / History /
Stats, Live default) above whichever sub-tab is selected:
- **Live** → `LiveScreen(...)` unchanged, called with the same params it
  always took.
- **History** → `HistoryScreen(...)`, moved out of `LogsScreen`'s own
  `TabRow` (was `LogTab.HISTORY`).
- **Stats** → `StatsScreen(visits = visits)`, moved out of the
  Settings-pushed `ROUTE_STATS` route (see §27).

**`ui/stats/StatsScreen.kt`** — its own `Scaffold`/`TopAppBar`/back-arrow were
stripped (Avi's explicit call over `AskUserQuestion`): as a peer sub-tab
inside Traffic, "back" had no destination to go to. `onBack` param removed
from the signature; content (the airline/private `TabRow`, list, bottom
sheet) is otherwise byte-for-byte what it was.

**`ui/logs/LogsScreen.kt`** — its `LogTab` enum and `TabRow` are gone; it is
now just the Events list. `onClearHistory`/`onShareHistory` params removed
(no longer needed once History left this screen).

**`ui/settings/SettingsScreen.kt`** — the "Aircraft stats" section
(`StatsSection`) and its `onOpenStats` param (both overloads) removed —
Stats is reachable from Traffic now, not from Settings.

**`MainActivity.kt`** — `ROUTE_STATS` and its `composable(ROUTE_STATS) { … }`
block deleted; the `AdsbDestination.LIVE.route` composable now renders
`TrafficScreen` (passing the same Live params plus `onClearHistory`/
`onShareHistory`, which used to go to `LogsScreen`); `SettingsScreen`'s call
site drops `onOpenStats`; `LogsScreen`'s call site drops both history params.

Full suite: 400 core + 78 app tests, 0 failures (no test referenced any of
the touched screens' signatures); debug APK builds. Version bumped to v1.6.5
before this build.

**Also folded into v1.6.5 — "max range mi" made session-scoped, not instantaneous.**
Previously `UiMapper.mapMetrics()` computed `aircraft.mapNotNull { it.distanceNm }.maxOrNull()`
every publish tick — a pure function of the *currently tracked* list, so it
dropped back down the moment the farthest aircraft aged out or left range.
Changed to track a running max for the whole receiver session instead:

- **`PipelineService._sessionMaxRangeNm`** (new `MutableStateFlow<Double?>`) —
  updated in `onAircraftUpdated()` (fired for every message merged into the
  aircraft table) via a plain `if (d > current) update` — no aircraft-list scan
  needed, since it already sees every state update as it happens.
- **Reset point: `clearSessionState()`**, the same function that already zeroes
  `stats`, `icaoCache`, and the in-flight lookup sets at every session
  boundary — called from `startPipeline()`, `restartPipeline()`/`reconnect()`
  (dongle replug goes through this via `hotplugReceiver.onAttached`), and the
  connection-loss retry loop. **Not** called from `resetStatsCounters()`
  (the overflow menu's "Reset counters"), so that action does not touch it —
  matches the ask ("resets only... after the app stops, or the dongle
  restarts"), not on every counter reset.
- Threaded through: `PipelineService.sessionMaxRangeNm` → `MainActivity`
  collects it into `MainViewModel.onSessionMaxRange()` → a new
  `_sessionMaxRangeNm` StateFlow combined into `liveMetrics` (as a second,
  nested `combine` rather than a 4-arg one — `kotlinx.coroutines`' typed
  4/5-arg `combine` overloads are `@FlowPreview`, so this avoids opting into
  a preview API for a straightforward addition) → `UiMapper.mapMetrics()` now
  takes `sessionMaxRangeNm: Double?` instead of deriving it from the aircraft
  list.
- Label changed from `"max range mi"` to `"Max Range(miles)"` in
  `LiveScreen.kt`'s `MetricTile` (verbatim wording as given, not the app's
  usual lowercase-terse style).

Full suite: 400 core + 78 app tests, 0 failures (no existing test asserted the
old instantaneous-max behavior); debug APK builds. Still v1.6.5/`versionCode`
20 — bundled into the same not-yet-released build as §33's nav change.

**Also folded into v1.6.5 — Traffic tab layout: tabs moved below the app bar, swipeable.**
Avi's follow-up on §33: the Live/History/Stats tab row was sitting above
everything, including Live's own top bar. Moved it to sit between that top
bar (tuner chip/title/Start-Stop/overflow — "the live start section", which
applies regardless of which sub-tab is open, so it stays fixed) and the
Airborne/On ground/Position/etc. filter chips, which are Live-only. Also
added horizontal swipe between the three sub-tabs, not just tapping the tab
row.

- **`ui/text/LiveScreen.kt`** split in two — nothing else about either half
  changed: **`LiveTopBar`** (tuner chip, "Live" title, Start/Stop button +
  its stop-confirm dialog, overflow menu) and **`LiveBody`** (metrics header,
  `FilterChipRow`, sort bar, aircraft list/non-nominal state). The old
  monolithic `LiveScreen` composable is gone — `TrafficScreen` is its only
  caller and now calls the two halves separately with the tab row and pager
  sandwiched between them.
- **`ui/text/TrafficScreen.kt`** rewritten: `LiveTopBar` first, then a
  `TabRow` driven by a `HorizontalPager`'s `pagerState.currentPage` (standard
  Compose tab+pager sync — tapping a tab calls
  `pagerState.animateScrollToPage()`, swiping the pager updates the tab
  selection automatically), then the pager itself with `LiveBody` /
  `HistoryScreen` / `StatsScreen` as its three pages.

Full suite: 400 core + 78 app tests, 0 failures; debug APK builds. Still
v1.6.5/`versionCode` 20.

## 34. Dongle reconnect race fixed; Traffic/Receiver Start-Stop-Reconnect unified (2026-08-05, v1.6.6)

Two independent fixes from the same review, not yet committed.

**Reconnect race.** `startPipelineInternal()`'s own retry loop reopens the
source on its own timer (`awaitRetry()` polling `UsbPresence`), entirely
outside `sessionLock` — only the *outer* `startPipeline()`/`restartPipeline()`
calls took that lock. `UsbHotplugReceiver.onAttached` → `restartPipeline()`
is, by design, a second independent trigger for the same reopen ("Both
callbacks are accelerators, not the recovery mechanism" —
`PipelineService.kt`'s hotplug doc comment). On a real replug these two could
fire near-simultaneously: the retry loop mid-`openUsbSource()` (an in-flight
`iqsrc://` request already sent to the driver app) gets cancelled by
`restartPipeline()`'s `teardownSession()`, which then launches a *second*
`iqsrc://` for the same device — the driver app gets two overlapping opens
and can get stuck holding the device, recoverable only by a full process
kill (closing the loopback socket at the OS level is what actually resets
the driver's state; a same-process Stop/Start doesn't). Fixed by wrapping the
retry loop's own `openUsbSource(source)` call in `sessionLock.withLock {}` too
— every path that can open a source now serialises through the same mutex,
so no two can ever be in flight together.

**Start/Stop/Reconnect unification.** `ReceiverAppBar`'s Start/Stop button was
hardcoded `"STOP"` regardless of state and called `onStop()` again (a no-op)
when already idle — there was no way to start from the Receiver tab except by
misusing Reconnect. Fixed to mirror Traffic's dynamic label
(`isRunning`-driven), and `ReceiverScreen` gained an `onStart` param wired
from `MainActivity`. New shared `ui/components/PipelineConfirmDialogs.kt`
(`StopConfirmDialog`, `ReconnectConfirmDialog`) replaces the two screens'
near-duplicate inline `AlertDialog`s — the drift that let Receiver's button
go stale in the first place — and both screens now confirm before Reconnect
too (previously silent in both), per Avi's call: a reconnect ends the session
the same way Stop does, so it gets the same guard.

Full suite: 400 core + 78 app tests, 0 failures; debug APK builds. Version
bumped to v1.6.6 (`versionCode` 21) before this build, then released.

## 35. FA's initial attempt made timer-driven, capped at 2 requests/sec (2026-08-07)

Root cause found while investigating why a real Canadian contact (C065DB, 2
messages, weak signal, no callsign) never got FA-enriched despite "10+
seconds" having passed: `FlightAwareEnrichment.maybeSchedule()` — the only
thing that ever checked whether the 4s/5s delay had elapsed — was itself only
ever called from `onAircraftUpdated()`, which only fires when a *new* message
arrives. An aircraft that stops transmitting decodable frames before a
message lands after the delay window never gets a second look, no matter how
much wall-clock time passes.

**Fix, not yet committed/released:**

- `FA_INITIAL_DELAY`: 4s → 5s.
- New 1s sweep coroutine (`sweepDueFirstAttempts()`, started in `init {}`)
  scans idents that have never had an attempt (`!faCache.containsKey(ident)`)
  and fires once `isFirstAttemptDue()` (new pure top-level fun, unit-tested)
  says the 5s delay has passed — independent of any new message. Deliberately
  scoped to **first attempts only**: retries (`FA_RETRY_INTERVAL` = 30s) stay
  gated behind a live message in `maybeSchedule()`, same as before. Sweeping
  retries too would mean an aircraft that's actually departed keeps getting
  retried forever by the timer instead of naturally falling silent once no
  more messages arrive for it — the sweep only had to cover the one case that
  was actually broken.
- New `FaRateLimiter` (sliding 1s window, unit-tested) caps every outbound FA
  request — sweep-triggered or message-triggered, first attempt or retry — at
  2/second, shared across all aircraft, so FlightAware never sees a burst
  from either path.
- New file `FaSchedulingTests.kt` (6 tests): delay-boundary cases for
  `isFirstAttemptDue`, and cap/window-slide/shared-budget cases for
  `FaRateLimiter`.

Full suite: 400 core + 84 app tests (6 new), 0 failures; debug APK builds.
Released as part of v1.6.7 (§36).

## 36. Idle watchdog and a new Exit action now actually release everything (2026-08-07, v1.6.7)

Follow-up to the investigation into whether the app "shuts down completely"
on dongle disconnect. Found the real gap: `MainActivity` calls
`bindService(..., BIND_AUTO_CREATE)` in `onCreate()` and only unbinds in
`onDestroy()` — bound for the Activity's whole lifetime, not just while
foregrounded. Under Android's service lifecycle rules, `stopSelf()` on a
service that's still bound clears the "started" flag but does **not**
destroy the instance — it stays resident, notification and background
coroutines included, until every bound client also disconnects. Since
nothing ever told `MainActivity` to unbind, the idle-source watchdog's
`stopSelf()` (§ "stopping to save battery") was very likely a no-op in
practice.

**`PipelineService.kt`:**
- New `releaseResources()` (private, suspend) — the single place that closes
  everything: unregisters the hotplug receiver, stops GPS updates, cancels
  the pipeline job and closes its source, closes all three loggers, and
  (new) closes `routeEnrichment`/`aircraftMetaEnrichment`/
  `flightAwareEnrichment`'s ktor `HttpClient`s, which were never explicitly
  closed before — they just leaked until the process died. `onDestroy()` now
  calls this instead of duplicating the same steps inline.
- Idle watchdog's `onExpire` now calls `releaseResources()`,
  `ServiceCompat.stopForeground(..., STOP_FOREGROUND_REMOVE)`, sets new
  `shutdownRequested: StateFlow<Boolean>`, then `stopSelf()` +
  `serviceScope.cancel()` — an actual full teardown, not just a flag that
  silently did nothing while bound.
- New public `fun exit()` — same full release, blocking (bounded 2s) so the
  caller can safely unbind/finish right after it returns instead of racing
  the async cleanup. Used by the new Exit action, not the watchdog.

**`RouteEnrichment`/`AircraftMetaEnrichment`/`FlightAwareEnrichment`:** each
gained a `fun close() = client.close()`.

**`MainActivity.kt`:** tracks `isBound` explicitly; collects
`service.shutdownRequested` and unbinds when it fires (fixes the watchdog
gap above). New `onExit` callback: calls `pipelineService.exit()`, unbinds,
`finishAffinity()`, then `Process.killProcess(Process.myPid())` — a
deliberate, non-default-Android-pattern choice (usually you'd just let the
OS reclaim an empty process) made because Avi explicitly asked for an
active, immediate release, not an eventual one. `connection`'s type was
made explicit (`ServiceConnection`) — the new self-referencing
`unbindService(connection)` call inside its own `onServiceConnected` hit a
Kotlin recursive-type-inference error without it.

**Settings → Exit section** (new `ExitSection`, bottom of the root page,
below About): a red "Exit app" button behind a new `ExitConfirmDialog`
(alongside `StopConfirmDialog`/`ReconnectConfirmDialog` in
`ui/components/PipelineConfirmDialogs.kt`).

Full suite: 400 core + 84 app tests, 0 failures; debug APK builds. Version
bumped to v1.6.7 (`versionCode` 22) before this build, then released.

## 37. Shutdown-race fix, POST_NOTIFICATIONS request, Exit in Traffic overflow (2026-08-08)

Follow-up to §36, after Avi asked whether the app truly stops everything on
Exit. Full fact-based investigation (grep + line-by-line trace, no
speculation) is preserved in full in the plan file used for this turn;
summary of what it found and fixed:

**Root-caused why no notification is ever visible.** `AndroidManifest.xml`
declares `POST_NOTIFICATIONS` but it is never requested at runtime anywhere
in the app (grepped `app/src/main/java` — zero hits outside the manifest).
On API 33+ (`targetSdk` 35), an unrequested runtime permission means every
`notify()` call — the ongoing receiver-status notification included — is
silently dropped, no crash, no sign to the user. The foreground service
still runs fully regardless (notification visibility and foreground-service
privilege are independent), so this explained the *missing notification*,
not by itself any battery/heat symptom.

- **`MainActivity.kt`**: added a `notificationPermissionLauncher`
  (`ActivityResultContracts.RequestPermission()`) fired from a
  `LaunchedEffect(Unit)` guarded on `Build.VERSION.SDK_INT >= 33`, same
  pattern as the existing `ACCESS_FINE_LOCATION` launcher just above it.

**Found and fixed a real shutdown-ordering bug.**
`PipelineService.releaseResources()` called `pipelineJob?.cancelAndJoin()`
with no timeout of its own, inside the same outer `withTimeoutOrNull` as the
socket-close step after it. `NetworkSource.readSamples()`
(`core/receiver/.../NetworkSource.kt:64-68`) wraps a plain blocking Java
`InputStream.read()`, which coroutine cancellation cannot interrupt — only
its own 3s socket timeout (`RtlSdrDefaults.IQ_READ_TIMEOUT_MS`) can. `exit()`
gave the whole function 2s, `onDestroy()` only 500ms — both shorter than
that worst case — so a mid-read pipeline at the moment of Exit could make
the outer timeout fire *before* `releaseResources()` ever reached the
socket-close line, skipping it (the fallback via the pipeline's own retry
loop catch block doesn't rescue this either — `cancelAndJoin()`'s `cancel()`
already fired, and `withContext` on an already-cancelled Job throws instead
of running). Process death from `killProcess()` still forces the socket
closed at the OS level shortly after regardless, so this was an ungraceful
~2s delay, not indefinite continued operation — but a real bug.

- **`PipelineService.kt`**: `releaseResources()` restructured to the
  standard Kotlin coroutines "must-run cleanup" idiom —
  `withTimeoutOrNull(1_500) { pipelineJob?.cancelAndJoin() }` inside a `try`,
  with the socket-close/logger-close/HTTP-client-close block moved into a
  `finally { withContext(NonCancellable) { ... } }`. `finally` blocks always
  run in Kotlin coroutines regardless of how the `try` exited (including
  cancellation), and `NonCancellable` is specifically immune to ambient
  cancellation from an enclosing timeout — together these guarantee the
  actual cleanup always completes, not just "usually." The now-redundant
  outer `withTimeoutOrNull(2_000)`/`withTimeoutOrNull(500)` wrappers in
  `exit()`/`onDestroy()` were removed — `releaseResources()` now bounds and
  guarantees itself internally.

**Exit app added to Traffic's three-dot menu**, not just Settings.
`LiveTopBar` (`ui/text/LiveScreen.kt`) gained an `onExit: () -> Unit` param,
a new `showExitConfirm` local state, an "Exit app" `DropdownMenuItem` in the
existing overflow menu (alongside Reconnect/Reset counters), and reuses the
same `ExitConfirmDialog` already built for Settings — threaded
`TrafficScreen` → `MainActivity`'s existing `onExit` (already wired to
Settings; this is a second entry point to the identical callback, not a
new implementation).

What remains genuinely unverifiable from this repo, stated as such rather
than guessed at: the RTL-SDR driver app (`marto.rtl_tcp_andro`) is a
separate third-party APK not in this repository. If it keeps the USB device
held/streaming after adsb-android's process dies — which this project's own
comments describe as observed, empirical behavior from past debugging, not
something re-verifiable by reading code here — that would fully explain
sustained heat/battery drain, and no change on adsb-android's side can
prevent it.

Full suite: 400 core + 84 app tests, 0 failures (no regressions — this is
Service lifecycle + Compose wiring, no new pure logic to unit-test); debug
APK builds. Version bumped to v1.6.8 (`versionCode` 23) before this build,
then released.

## 38. Airlines.kt expanded 155 → 1,052 entries via OpenFlights (2026-08-09, v1.6.9)

Requested feature was "scrape planespotters.net for airline data, embed it,
use it for callsign → airline lookups." Investigated that specific site
first — it's not viable: the current `/airlines` index page no longer has
the table markup a reference scraper expected, and every individual
`/airline/<name>` detail page (confirmed on three different airlines)
redirects to a live Cloudflare-style bot challenge, not real content. No
change proceeded on that path; Avi agreed to substitute a data source that
actually works for the same underlying goal — offline airline-name coverage
for `Airlines.fromCallsign()`.

**Source: OpenFlights' `airlines.dat`** (https://openflights.org, ODbL
license) — a maintained, freely-downloadable CSV with exactly the shape
needed (Name, IATA, ICAO, Callsign, Country, Active), no scraping or bot
wall involved. Cached at `tools/openflights_airlines.dat` (6,162 rows as of
this fetch); refresh with the `curl` command in `gen_airlines.py`'s
docstring.

**`tools/gen_airlines.py` rewritten** to merge two sources instead of one:
- The Python reference's `_AIRLINE_MAP` (155 entries, verified against real
  traffic) — **always wins on a conflicting ICAO prefix.**
- OpenFlights entries filtered to `Active == "Y"` with a real 3-letter ICAO
  code — only fills prefixes the reference table doesn't already cover.
- Filter bug caught and fixed during this same pass: Python's `str.isalpha()`
  accepts *any* Unicode letter, not just A–Z — an early run let through a
  handful of non-Latin ICAO codes (e.g. Cyrillic `КТК`) that can never match
  a real ADS-B callsign (always ASCII per the Mode S encoding). Fixed to
  `icao.isascii() and icao.isalpha()`. Non-Latin *airline names* (e.g. `PKV`
  → `Псковавиа`) are legitimate and kept — only the lookup key was ever the
  problem.
- `Airlines.kt`'s header doc comment updated to describe both sources and
  carries the ODbL attribution.

**Result: 1,052 total** (155 reference + 897 new from OpenFlights).

**Two pre-existing tests updated** for the now-expected larger table
(`core/receiver/.../enrich/EnrichmentTests.kt`):
- `table was generated, not left empty` asserted an exact `155` — changed to
  a `>= 1000` floor, since the merged count will drift slightly whenever the
  OpenFlights cache is refreshed; a floor is what actually catches "generation
  silently produced an empty table," which is the test's real intent.
- `unknown or unusable callsigns return null` used `"ZZZ9999"` as a
  "definitely unmapped prefix" example — OpenFlights turned out to have a
  real `ZZZ` → Zabaykalskii Airlines entry, so that assumption broke.
  Replaced with `"XQZ9999"`, confirmed absent from the merged table.

Full suite: 400 core + 84 app tests, 0 failures; debug APK builds. Version
bumped to v1.6.9 (`versionCode` 24) before this build, then released.

## 39. adsbdb field-mapping bug fixed — aircraft type was always incomplete (2026-08-09, v1.6.10)

Root-caused why ICAO C066C4 (Canadian, Harbour Air Cessna 172M, C-GMXV)
showed no aircraft type at all. `AdsbdbAircraftFields`
(`app/.../data/AircraftMetaEnrichment.kt`) declared a `registerType` field
for the ICAO type designator — that field **does not exist anywhere in
adsbdb's real response** (verified live: `{"type":"172M","icao_type":"C172",
"manufacturer":"Cessna",...}`, no `registerType` key at all). With
`ignoreUnknownKeys = true` this never crashed — it silently deserialized to
`null` on every single adsbdb-sourced aircraft, so `model` was always null,
and `typeDisplay()`'s `"$manufacturer $model"` branch could never fire for
this source; at best it fell through to a bare, non-ICAO-mapped fallback
string.

**Fix**: added `@SerialName("icao_type") val icaoType` to
`AdsbdbAircraftFields`, and swapped the mapping — `typeCode` now reads from
`icao_type` ("C172"), `model` now reads from the old `type` field ("172M",
free text) — matching the same field-role split hexdb.io's response already
used (`ICAOTypeCode` vs `Type`).

**Extracted for testability**: the field→`AircraftMeta` mapping moved out of
`fetchAdsbdb()` into a pure top-level `internal fun mapAdsbdbFields()`, and
`AdsbdbAircraftFields` promoted from file-private to `internal` so tests can
construct it directly — same pattern as `isFirstAttemptDue`/`FaRateLimiter`
in §35. New `AdsbdbFieldMappingTests.kt` (3 tests) uses C066C4's real
captured response shape as the fixture.

Full suite: 400 core + 87 app tests (3 new), 0 failures; debug APK builds.
Version bumped to v1.6.10 (`versionCode` 25) before this build, then released.

## 40. In-flight lookup sets were plain HashSets mutated across two dispatchers (2026-08-09, v1.6.11)

Investigated why C084A3 and C02AE3 (screenshots: Air Canada Rouge A321 and
WestJet 737 MAX 8, both with complete hexdb.io data available) never got
registration/type/route despite the session running long enough that timing
wasn't the explanation. Ruled out a parsing bug first: extracted
`fetchHexdb()`'s field mapping into a pure `mapHexdbResponse()` and ran it
against both aircraft's real captured responses — both parse correctly
(new `HexdbFieldMappingTests.kt`, 2 tests) — so the app can understand this
data fine when it gets a chance to fetch it.

**Root cause: `routeLookupInFlight`/`metaLookupInFlight` were plain
`HashSet<String>`, mutated from two different dispatchers.** `add()` runs on
`ReceiverRepository`'s confined dispatcher (`onAircraftUpdated`); `remove()`
runs inside `serviceScope.launch(Dispatchers.IO) { ... }` once the async
network call finishes — a different thread pool entirely. `java.util.HashSet`
is explicitly documented as unsafe for concurrent modification from multiple
threads without external synchronization. With several aircraft in view at
once (exactly the screenshots' scenario — 3-4 simultaneous contacts, each
independently triggering its own async lookup+removal), a `remove()` for one
ICAO racing a concurrent `add()`/`remove()` for a different ICAO on the same
underlying hash table could get silently lost. Once that happens, that ICAO
stays marked "in flight" for the rest of the process's lifetime —
`metaLookupInFlight.add(icao)` returns `false` forever after, so every future
message for it skips re-enrichment permanently. No amount of additional time
fixes this; only an app restart (a fresh in-memory set) does.

**Fix**: both sets swapped for `ConcurrentHashMap.newKeySet<String>()` —
lock-free, genuinely thread-safe, drop-in API-compatible
(`add`/`remove`/`clear` all still work), and the same JDK-concurrent-collection
pattern this codebase already uses for `PipelineStats.dfCounts`.

No dedicated regression test for the race itself — concurrent-corruption bugs
depend on precise thread interleaving and would make a flaky, not a reliable,
test. The fix is correct by the Java Collections Framework's own documented
contract, not something that needs empirical reproduction to trust.

Full suite: 400 core + 89 app tests (2 new), 0 failures; debug APK builds.
Version bumped to v1.6.11 (`versionCode` 26) before this build, then released.

## 41. Four more base-map tile sources — CARTO + Esri Street (2026-08-09)

Avi didn't like OSM's colors/layers/style. Rather than keep tuning the
`darkTileFilter()` invert-hack (§29–30, OSM-only), added four real basemap
styles as new `BaseMap` entries (`core/receiver/.../map/BaseMap.kt`):
`ESRI_STREET` (Esri World Street Map, same `{z}/{y}/{x}` tile order as the
existing `ESRI_IMAGERY`), `CARTO_DARK` (Dark Matter), `CARTO_VOYAGER`, and
`CARTO_POSITRON` — all free, no API key, plain `{z}/{x}/{y}` XYZ raster,
`labelUrlTemplate = null` since all four already carry their own labels.

No other code changes needed: Settings' "Base map" section already renders
`BaseMap.entries.forEach { ... }` (`SettingsScreen.kt:573`), so the four new
options just appear. `tileFilterFor()` (`MapScreen.kt:325`) only applies the
invert hack `if (baseMap == BaseMap.OSM)`, so `CARTO_DARK` renders as its own
real dark tiles, not an inverted OSM — this is the actual fix for the
complaint, not just another option next to the same problem.

`:core:receiver:test` + `:app:testDebugUnitTest` pass, unchanged counts (no
new testable logic — this is static enum data). Version bumped to v1.6.12
(`versionCode` 27), debug APK built and added to `dist/`. Not pushed.

## 42. Zoom-step widening, ring color/width/line-type, Base map + rings moved into the Map tab (2026-08-09, v1.6.13)

Three Map-tab changes, planned and approved before coding per Avi's request.

**+/- zoom "doesn't work" on the 4 new basemaps.** Investigated first —
curled all 6 basemap tile URLs directly, all returned HTTP 200 with real
tile bytes (including with the app's bare package-name User-Agent), and
`+`/`-` (`MapScreen.kt` `MapControls`/`ControlButton`) has zero
basemap-conditional code; it only reacts to a local `rangeStep` state. No
basemap-specific root cause could be confirmed. `RangeStep`
(`MapScreen.kt:81-95`) widened from 4 to 6 steps (`R3_6` through
`R100_250`, the far end matching `AppConfig.MAX_MAP_RING_MI`) so `+`/`-`
rarely hit the disabled ends — the fix direction Avi approved regardless of
root cause. Flagged in the plan: if the complaint still reproduces on a
specific basemap after this ships, that's real signal for an on-device
logcat investigation, not another guess.

**Configurable range-ring color/width/line style.** New file
`ui/map/RingStyle.kt`: `RingColorPreset` (6 swatches — Cyan/Amber/Green/
Red/White/Grey, Cyan matching the old hardcoded look), `RingWidth` (Thin/
Medium/Thick, Thin = old default), `RingLineStyle` (Solid/Dashed/Dotted,
Solid = old default). Three new `AppConfig` fields
(`mapRingColor`/`mapRingWidth`/`mapRingLineStyle`), persisted in
`AppConfigStore` the same `valueOf`/`.name` pattern as `mapBaseMap`.
`AircraftOverlay.drawRings()` now reads `ringColorArgb`/`ringWidthDp`/
`ringLineStyle` instead of a hardcoded `AdsbColors.Primary`/1dp/solid —
dashed/dotted done via `DashPathEffect` (+ round stroke cap for dots).
Defaults reproduce the exact old visuals, so nothing changes until a user
picks something else.

**Base map + range-ring settings moved from Settings into the Map tab's
Layers panel.** `SettingsScreen.kt`'s `BaseMapSection`/`MapRingsSection`
deleted outright (not duplicated). `MapScreen.kt`'s `LayersPanel` — widened
186dp → 230dp, height-capped and made scrollable
(`verticalScroll`/`heightIn(max = 420.dp)`) since it now also holds a
6-entry base-map picker, up to 5 ring-radius rows, and the 3 new ring-style
pickers — gained: base-map picker (reusing `OptionRow` from
`ui/settings/SettingsComponents.kt`, now imported into `MapScreen.kt`),
ring-radius rows (ported from the deleted `MapRingsSection`, reusing
`SettingsField`), a new `ColorSwatchRow`, and a new generic `PillRow`
(factored out and reused 3× — trail length, ring width, ring line style —
instead of copy-pasting the pill-row pattern).

`:core:receiver:test` + `:app:testDebugUnitTest` pass (no new pure logic —
this is Compose UI + Android `Paint` wiring, not unit-testable without
Robolectric/instrumentation). Debug APK built, v1.6.13 (`versionCode` 28),
added to `dist/`. Not yet verified on-device — pending Avi confirming the
zoom-step fix actually resolves the original complaint.

## 43. Enrichment: fix the literal-"null" bug class-wide, merge meta sources instead of first-wins (2026-08-09)

Screenshot showed `C01286`/`C040F9`/`C0001C` (Canadian aircraft) with type
line "Null" and route "null → null". Root-caused: `FlightAwareEnrichment.kt`'s
`parse()` (§ investigation this session) walks FlightAware's raw JSON tree by
hand; when a field is present but JSON `null` (not absent), kotlinx.serialization's
`JsonNull.content` is the literal string `"null"`, which the old `?: ""`
fallback never caught (a present key never hits the fallback). Avi then asked
for "complete data collection," not just this one patch — two Explore
surveys of the whole enrichment pipeline found the same bug class in a
second file and a real completeness gap unrelated to any bug.

**Shared the `.present()` filter everywhere.** New file
`data/StringPresence.kt`: `.present()` (trims, treats literal `"null"`
case-insensitive as absent) moved here from its private copy inside
`AircraftMetaEnrichment.kt` (same logic, now shared). `IcaoLookup.kt`'s
duplicate `presentOrNull()` deleted, its 2 call sites use the shared one.
`FlightAwareEnrichment.kt`'s `parse()` now runs every extracted field
(origin/destination/callsign/airline name+icao/typeCode/manufacturer/model,
plus the `flightStatus` check that gates which flight record is picked)
through `.present()` before use — `titleCase("null")` can no longer produce
`"Null"`. `RouteEnrichment.kt`'s `icaoCode` extraction filtered too
(defensive; adsbdb hasn't been observed doing this, but now can't leak it if
it starts). New tests: `StringPresenceTests.kt` (4), and
`FlightAwareEnrichmentNullFieldTests` in `FlightAwareEnrichmentTests.kt` (3)
proving a JSON-null origin/destination/aircraft field no longer produces the
string `"null"`/`"Null"`.

**`AircraftMetaEnrichment` merges sources instead of stopping at the first
one that returns anything.** `lookupUs`/`lookupIntl` used to `return` the
first non-null result from hexdb → OpenSky → (adsbdb) — a source with only a
registration permanently hid a later source's owner/type, since the first
one "succeeded." Rewrote both to fetch every applicable source **in
parallel** (`coroutineScope` + `async`, so latency stays ~max(sources) not
sum — this already runs off the decode path on `Dispatchers.IO`, never
blocks decoding) and combine field-by-field via a new pure, testable
top-level `mergeSources()`: first non-null value per field wins in the
existing priority order (hexdb > OpenSky > adsbdb), `source` becomes a
joined `"hexdb+adsbdb"`-style label (confirmed only used for the `"none"`
negative-cache sentinel, never shown in UI — safe to change). Deliberate
tradeoff: always queries every source now instead of stopping early, more
requests per aircraft against free hobbyist APIs — accepted, this is "try
harder once" per aircraft (2h re-cache), not continuous polling. New
`MergeSourcesTests.kt`-equivalent (`MergeSourcesTests` class in
`AircraftMetaEnrichmentTests.kt`, 4 tests) proves two partial sources
combine into one complete result and same-field conflicts keep the
higher-priority source's value.

**`RouteEnrichment`'s negative-cache TTL: 24h → 2h**, matching
`AircraftMetaEnrichment`'s already-fixed convention (§31, 30 days → 2h) — a
transient adsbdb hiccup no longer locks out a retry for a full day.

**Two gaps investigated, not fixed this round, findings recorded for a
follow-up decision:**
- Callsign-less aircraft can't get an adsbdb route-by-callsign lookup
  (protocol-level — adsbdb's `/v0/aircraft/{hex}` genuinely has no route
  fields, confirmed live) — but **already covered in practice**:
  `FlightAwareEnrichment`'s existing ident fallback (callsign → registration
  → bare ICAO, `PipelineService.kt:1115-1117`) was verified live against
  `flightaware.com/live/flight/CFHAJ` (bare registration, no callsign) and
  returned a real `trackpollBootstrap` payload with route data. Nothing to build.
- Non-US offline registration coverage is genuinely thin: `Registration.kt`
  is algorithmically US-only by construction (most countries' Mode-S
  addresses aren't invertible the way FAA N-numbers are — needs a lookup
  table, not code); the bundled `app/src/main/assets/icao_db.json` is a
  **20-entry placeholder** (verified by reading it — no Canadian entries at
  all, confirming the screenshot's Canadian registrations came from a live
  network lookup, not this file). Best fix path identified but not started:
  regenerate `icao_db.json` from OpenSky Network's bulk aircraft database
  (`opensky-network.org/datasets/metadata/aircraftDatabase.csv`, confirmed
  live, 94.5 MB, free, no key — same provider already queried live in
  `AircraftMetaEnrichment.fetchOpenSky()`), same `tools/gen_X.py` pattern as
  `Airlines.kt`. License needs confirming before bundling (likely CC BY-NC,
  unverified). `tar1090-db` (the readsb/tar1090 ecosystem's usual DB)
  considered and set aside — no declared license on its GitHub repo.
  Writing additional scrapers (FlightRadar24, RadarBox, per-country
  registries) considered and rejected: aggressively bot-protected or a
  large per-country maintenance burden for what the OpenSky bulk import
  already covers with a documented, already-trusted source.

`:core:receiver:test` + `:app:testDebugUnitTest` pass, 11 new tests, 0
failures. Version bumped to v1.6.14 (`versionCode` 29), debug APK built and
added to `dist/`.

## 44. Map zoom race fix, ring contrast, Layers panel auto-close, EditableStepperRow for auto-stop/GPS refresh, combined+rounded GPS location field (2026-08-14, v1.6.15)

Seven-item punch list from Avi.

**+/- zoom buttons genuinely did nothing, on every basemap** (the widened
6-step ladder in §42 didn't fix it — root cause was elsewhere). Found it:
`MapScreen.kt:241-246` had two `LaunchedEffect`s both keyed on `rangeStep` —
one re-centers on the observer (`animateTo`), the other zooms
(`zoomTo(…, 300L)`). `followObserver` defaults `true` and only flips off
once the map is dragged once, so on a fresh screen every `+`/`-` tap fired
**both** camera animations in the same frame; osmdroid running a pan
animation and a zoom animation concurrently silently swallows one of them.
Fix: dropped `rangeStep` from the recenter effect's keys — recentering only
needs to happen when `followObserver` toggles on or the observer's own
position moves, not when the zoom range changes.

**Range ring contrast.** `AircraftOverlay.kt`'s ring alpha (`0.08–0.22`) was
tuned for one color (cyan) against one basemap look before 6 ring colors and
6 basemaps existed. Added a dark (`AndroidColor.BLACK`) halo stroke —
`ringHaloPaint`, 2dp wider than the ring itself, drawn first — under the
main colored stroke (`drawRings()`), same treatment for ring labels via a
new `ringLabelHaloPaint` (stroke-style text drawn under the fill). Base
alpha raised to `0.30–0.55`. Standard cartography technique (the reason map
labels/lines everywhere get an outline) — guarantees contrast regardless of
ring color × basemap combination rather than needing per-combination tuning.

**Layers panel auto-closes on basemap pick** (`MapScreen.kt` `LayersPanel`'s
`BaseMap.entries.forEach` `OptionRow`) — `onClose()` added after
`onConfigChange`. Ring color/width/style pickers deliberately left open
(multi-adjustment, unlike a one-shot basemap pick).

**Auto-stop and GPS refresh interval converted from 5-6 preset `OptionRow`s
to `EditableStepperRow`** (`ui/settings/SettingsComponents.kt:336`, Avi's
established "Editable Stepper" pattern — `[ − | typed value | + ] unit`,
already used for accept-rate settings) — typed input + step buttons instead
of a fixed option list. Auto-stop: 1-60 min, step 1 (`0` still means "stay
running", built into `EditableStepperRow`'s existing Off semantics). GPS
refresh: 15-360 min, step 15. `WATCHDOG_OPTIONS`/`GPS_REFRESH_OPTIONS`
constants deleted (no longer referenced anywhere).

**Observer coordinates**: "FALLBACK COORDINATES" label renamed to "My
location" (kept "Coordinates" for fixed-mode, not GPS-fallback, per Avi's
literal ask). The two separate Latitude/Longitude `SettingsField` boxes
merged into one `"lat, lon"` field, split/parsed on edit. New
`Double.roundToGpsPrecision()` (`AppConfig.kt`, one-line extension —
`round(x * 1e6) / 1e6`) applied both at manual entry and at the "Update
GPS" fresh-fix path (`PipelineService.kt:591`) — GPS hardware accuracy tops
out around 5-6 meaningful decimal digits (~11cm/digit at 6dp), anything
beyond was floating-point noise the field was displaying as if it meant
something. The continuous GPS-follow path (`onGpsFix`/`observerPosition`,
line ~619) deliberately left untouched — that's an ephemeral live-tracking
value never shown in a text field, out of scope for this ask.

`:core:receiver:test` + `:app:testDebugUnitTest` pass (no new pure logic —
UI wiring + a trivial one-line rounding extension, not worth a dedicated
test per this session's established bar). Version bumped to v1.6.15
(`versionCode` 30), debug APK built and added to `dist/`.

**Correction (v1.6.16)**: item 3 above was the wrong behavior — Avi wanted
the Layers panel to close on tapping the *map*, not on picking a basemap
(basemap selection now behaves like every other Layers control: applies
and stays open). Reverted the `onClose()` call in the basemap `OptionRow`.
Added the actual fix in the map's touch overlay
(`MapScreen.kt` `onSingleTapConfirmed`, `DisposableEffect(mapView, overlay)`
block): a tap on the map itself now sets `layersOpen = false` first, before
its existing marker-hit-test logic runs — same "tap outside to dismiss"
convention as any other open panel/menu. `:app:testDebugUnitTest` +
`:app:assembleDebug` pass. Version bumped to v1.6.16 (`versionCode` 31).

## 45. v2.0 — osmdroid/raster replaced by MapLibre + OpenFreeMap vector tiles (2026-08-14)

Full map-rendering-engine replacement, per Avi's explicit direction (freeze
v1.6.16 as a base copy first — done, `adsb-android-v1.6.16-20260814/` inside
the repo root, source-only, gitignored, same convention as the prior
v0.x/v1.x snapshots). Scope, confirmed with Avi before coding: full
replacement (not an added option), offline required day one, full aircraft
overlay parity in one pass — the largest single change this session.

**Dependency**: `org.maplibre.gl:android-sdk`. Latest (13.5.0) needs Kotlin
2.2 metadata; this project is on 2.0.21 and bumping the whole toolchain
would cascade into KSP/Compose-compiler/Hilt versions too — pinned `12.0.0`
instead, verified with a real `:app:compileDebugKotlin` run, not assumed.
Real API surface (class list, method signatures) confirmed by inspecting
the downloaded AAR directly with `javap` — this sandbox can't reach
maplibre.org's docs (browsing blocked), so nothing here is from memory of
the old Mapbox SDK it forked from.

**`BaseMap`** (`core/receiver/.../map/BaseMap.kt`): raster `urlTemplate`/
`labelUrlTemplate` replaced by a single `styleUrl` per entry — 4 verified-live
OpenFreeMap styles (`tiles.openfreemap.org/styles/{liberty,bright,positron,dark}`,
all confirmed 200 with real MapLibre style JSON). Default changed
`BaseMap.OSM` → `BaseMap.LIBERTY`.

**`MapScreen.kt`**: osmdroid's synchronous `MapView`/`controller` replaced
with MapLibre's async `MapView.getMapAsync{}` → `MapLibreMap.setStyle{}`
lifecycle. The two-effect camera bug fixed in §44 doesn't exist in the new
code by construction — recenter and zoom are now one `LaunchedEffect`
computing a single combined `CameraUpdateFactory.newLatLngZoom()` call,
not two effects that could race. `computeZoom()`'s web-mercator math is
unchanged (MapLibre uses the same zoom-level convention). Base-map switching
calls `setStyle()`, which — a real architectural difference from raster —
**discards every previously added source/layer**, so a new aircraft-overlay
object is rebuilt against the new `Style` on every switch, not reused.

**New `ui/map/AircraftMapLayer.kt`** replaces the old Canvas-`Overlay`-based
`AircraftOverlay.kt` (deleted): GeoJSON sources + style layers instead of a
`draw()` loop. Per-feature visuals (shape/color/rotation/opacity) are
computed in Kotlin exactly like the old `drawMarker()` did, and relayed via
plain `Expression.get()` — deliberately avoiding complex `match`/`switchCase`
style expressions neither testable nor verifiable on-device from here.
Range rings are geodesic circle polygons (`AircraftMapLayer.circlePoints()`,
haversine destination-point formula, new `AircraftMapLayerTests.kt`, 4
tests) rendered via `LineLayer` — MapLibre's `CircleLayer` radius is
screen-pixels, not geographic distance, so it would not have kept the
"this ring is N real nautical miles" property across zoom the way the old
Canvas rings did. Selection/RA/emergency rings are filtered `CircleLayer`s;
labels are two `SymbolLayer`s (collision-managed default + an always-shown
one for selected/RA/emergency, `text-allow-overlap` + a filter — native
primitives doing what the old custom label-collision grid hash did by hand).
Clustering keeps the exact old trigger thresholds (>150 total, or zoom<8
with ≥20) computed in Kotlin, but hands the actual grouping to MapLibre's
native `GeoJsonSource` clustering once triggered — a different underlying
algorithm (radius-based) than the old fixed 90px screen grid, so cluster
bubble boundaries won't be pixel-identical, just conceptually the same.
**Known, deliberate visual approximations, not oversights**: stale aircraft
render dimmed (`icon-opacity`) rather than truly hollow-outline (no
single-bitmap way to do stroke-only recoloring via `icon-color`); label
backgrounds are a `text-halo` glow instead of a solid rectangle (`SymbolLayer`
has no built-in background box).

**Offline maps — not a port, a replacement, and it had to be.** The v1
system (`core/receiver/.../offline/*` + `app/.../offline/AndroidOfflineAdapters.kt`
+ `OsmTileDownloader.kt`/`OsmdroidCacheSource.kt`, ~2,900 lines total:
manifest/segment tracking, radius+detail byte estimation, cache import,
append-to-existing-segment targeting, travel-suggestion prompts, resumable
downloads) was built entirely around raster tiles being individually
addressable `z/x/y.png` files on disk. MapLibre's native offline model
(`OfflineManager`/`OfflineRegion`, real API verified via `javap`, not
guessed) manages vector tiles + sprites + glyphs + style JSON together as
one opaque, natively-cached region — no per-tile file access, no cheap
pre-download byte estimate, no cache import, no append/merge. None of that
sophistication has an equivalent to port to. Deleted outright: `OfflineMapManager.kt`,
`AppendTargeting.kt`, `TravelCoverage.kt`, `SegmentNaming.kt`,
`OfflineSegment.kt`, `OfflinePorts.kt`, `TileGeometry.kt`, `OfflineRadius.kt`,
`OsmTileDownloader.kt`, `OsmdroidCacheSource.kt`, and every test file
exercising them (5 core:receiver test classes, 2 app-side). Kept: `NetworkEligibility.kt`
(Wi-Fi-only gating — pure policy, tile-format-agnostic) and `AndroidLocationNamer`
(reverse-geocoding for a downloaded area's display name) — both genuinely
reusable. New `MapLibreOfflineRepository.kt` wraps `OfflineManager` in
suspend functions/a `Flow<OfflineDownloadEvent>`; `RegionMeta` (name +
timestamp, stashed in a region's opaque metadata bytes) uses manual
newline-delimited encoding rather than kotlinx.serialization — `:app` never
carried that compiler plugin (only `:core:receiver` does), not worth adding
for two fields (new `RegionMetaTests.kt`, 4 tests). `OfflineMapsViewModel.kt`/
`OfflineMapsScreen.kt` rewritten to the simpler model: one fixed-radius
(50nm, zoom 4–12) "download around current position" action, a list of
saved regions with delete — no radius/detail picker, no import, no append,
no travel suggestions. `PipelineService.kt`'s travel-tracking hook
(`noteTravel()`, called from `onGpsFix`) deleted — it only ever called into
the now-gone travel-coverage system. `AppConfig`'s raster-download-endpoint
fields (`offlineTileUrlTemplate`, `offlineDownloadEnabled`, `offlineDownloadConfigured`,
`effectiveTileUrlTemplate`) and Settings' "Offline map source" section
removed — meaningless once there's no separate download-endpoint concept.
`AppConfig.offlineMode` itself (the broader "stop all internet use" toggle,
also gates enrichment) is unrelated and untouched.

**Also removed**: the `osmdroid-android` Gradle dependency (`libs.versions.toml`,
`app/build.gradle.kts`) — nothing references it anymore.

**Testing, and its real limits.** `:core:receiver:test` (319) +
`:app:testDebugUnitTest` (90) pass, 0 failures — down from the pre-v2.0
counts because ~85 tests exercised the deleted raster offline system; up by
8 new tests (`AircraftMapLayerTests`, `RegionMetaTests`) for the new pure
logic that has any. `:app:assembleDebug` succeeds — real APK, real
`libmaplibre.so` packaged (confirmed in the build log), 71.5 MB debug build
(up from ~22 MB; MapLibre's native library is much larger than pure-Kotlin
osmdroid — expected, not a leak). **What none of this proves**: this
sandbox has no connected device (`adb devices -l` empty) and this
environment's browser is blocked from external sites, so nothing here has
been visually verified — not the map actually rendering OpenFreeMap tiles
on a real screen, not marker/ring/trail/cluster rendering, not the async
`getMapAsync`/`setStyle` lifecycle behaving correctly under real GPU/native
timing, not offline downloads actually completing against a real network.
Compiling and passing unit tests is real signal that the code is
structurally sound and grounded in the real MapLibre API — it is not the
same as having seen it work. On-device verification is the required next
step before trusting this, more so than any other change this session.

Version bumped to v2.0.0 (`versionCode` 32), debug APK built and added to
`dist/`.

## 46. Variable offline download radius + basemap label size/color overrides (2026-08-14, v2.0.1)

Two follow-ups to §45, requested together.

**Offline download radius, was fixed at 50nm.** New `AppConfig.offlineRadiusNm`
(default 50, `OFFLINE_RADIUS_MIN_NM`/`MAX_NM`/`STEP_NM` = 10/200/10),
persisted like every other setting. `OfflineMapsViewModel` loads/saves it
through the same `AppConfigStore` it already had; `OfflineMapsScreen.kt`
exposes it via the existing `EditableStepperRow` (Avi's established
"Editable Stepper" pattern, same component already used for auto-stop and
GPS refresh interval) instead of the old hardcoded description text. Zoom
range (4–12) and style (whatever base map is selected) stay fixed — only
radius was asked for. Known minor quirk: `EditableStepperRow`'s "Off"
button unconditionally sets 0, which `setRadius()` immediately clamps back
up to the 10nm floor — so "Off" reads oddly but can't actually break
anything. Not worth forking the shared component for.

**Basemap label font size/color** — genuinely uncontrolled before this
(flagged honestly when Avi asked): `MapScreen.kt` just handed OpenFreeMap's
style URL to `setStyle()` and rendered whatever labels/fonts/colors
OpenFreeMap authored, no app-side hook at all. Investigated by actually
fetching all 4 real style JSON documents and diffing their label layers —
**confirmed live, not assumed**: Liberty and Bright share the same 23
label-layer IDs (`label_city`, `highway-name-major`, etc.), but Positron
drops 4 (no POI labels) and **Dark uses a completely different naming
scheme** (`place_city` instead of `label_city`, `water_name` instead of
`water_name_point_label`, etc.) — a hardcoded layer-ID list would have
silently done nothing on Dark specifically.

Fix: new `ui/map/BasemapLabelStyler.kt` fetches the *same* style JSON
MapLibre already loads internally (a small redundant request, once per
style switch — the Kotlin SDK doesn't reliably expose reading a loaded
native layer's current property values, so re-parsing the JSON is the
simplest reliable way to discover which layer IDs are actually labels:
any layer with a `layout.text-field`) and applies overrides via
`Layer.setProperties()` (verified via `javap` against the real AAR — a
base-class method, works on any layer type generically, not just
`SymbolLayer`). New `AppConfig` fields: `mapLabelSize: MapLabelSize`
(`DEFAULT`/`SMALL`/`MEDIUM`/`LARGE`, new `ui/map/MapLabelSize.kt`) and
`mapLabelColor: RingColorPreset?` (nullable — reuses the existing ring
color palette rather than inventing a new one). **`DEFAULT`/`null` means
"don't touch"** — OpenFreeMap's own per-layer, zoom-responsive sizing
(country names bigger than village names, labels fading in by zoom) is
preserved unless Avi deliberately overrides it; picking a size flattens
every label layer to one fixed size, picking a color applies it uniformly
across all label layers, both trading OpenFreeMap's per-category nuance
for simple, predictable, working control — a deliberate simplification,
not an oversight.

New Layers-panel controls (`MapScreen.kt`): "BASEMAP LABEL SIZE" (`PillRow`,
reusing the pattern already used for ring width/style) and "BASEMAP LABEL
COLOR" (new `NullableColorSwatchRow` — the existing `ColorSwatchRow` has no
concept of "no selection," so this is a small separate composable with a
leading "×" default swatch, rather than changing the ring-color control's
established non-nullable contract). Label-layer IDs are cached per style
switch (`labelLayerIds` remember state) so changing just size/color doesn't
re-fetch the JSON.

`:core:receiver:test` + `:app:testDebugUnitTest` pass, `:app:assembleDebug`
succeeds. No new pure logic worth a dedicated test — this is Compose UI
wiring plus a thin JSON-parsing wrapper around a verified-working
technique already tested elsewhere in the codebase. Version bumped to
v2.0.1 (`versionCode` 33), debug APK built and added to `dist/`. Not
visually verified, same standing caveat as §45 — no device connected, no
external browser access in this sandbox.

## 47. APK size: restrict to arm64-v8a only (2026-08-14, v2.0.2)

The 68MB APK size flagged in §45 traced to a real, measured cause: unzipped
`dist/adsb-android-v2.0.1.apk` and summed `lib/<abi>/*.so` — MapLibre's
native library ships prebuilt for 4 ABIs (arm64-v8a 12.4MB, armeabi-v7a
9.0MB, x86 12.7MB, x86_64 12.7MB, ~47MB total), and the build had no ABI
filter, so all 4 shipped in one APK even though any one device only ever
uses one. This app is side-loaded onto Avi's one phone (Samsung SM-S928B,
arm64), not distributed via Play — no reason to carry the other three.

`app/build.gradle.kts`: `defaultConfig { ndk { abiFilters += "arm64-v8a" } }`.
Rebuilt and re-measured, not assumed: **68MB → 35.4MB**, single
`arm64-v8a` `.so` confirmed via the same unzip-and-sum check.
`:core:receiver:test` + `:app:testDebugUnitTest` pass. Version bumped to
v2.0.2 (`versionCode` 34), debug APK built and added to `dist/`.

If this app is ever built for a different device (an x86 emulator, an
older 32-bit phone), the `abiFilters` line needs the matching ABI added or
removed — this is a hard filter, not a fallback.

## 48. Removed the MapLibre logo, kept OSM attribution (2026-08-14, v2.0.3)

`MapScreen.kt`: `m.uiSettings.isLogoEnabled = false`, confirmed via `javap`
against the real AAR (`UiSettings.setLogoEnabled`/`setAttributionEnabled`
are separate toggles). Deliberately left attribution on — that's not
MapLibre branding, it's the OpenStreetMap/OpenFreeMap data license's
required credit (ODbL), a legal question distinct from a cosmetic logo,
and wasn't asked to be removed. `:app:testDebugUnitTest` passes. Version
bumped to v2.0.3 (`versionCode` 35), debug APK built and added to `dist/`.

## 49. Enrichment audit log: per-aircraft DETECTED/attempt/MOVED_TO_HISTORY timeline + manual retry (2026-08-14, v2.0.4)

Triggered by a live investigation of ICAO C027D7 showing no enrichment
despite hexdb.io and adsbdb both having real data for it (confirmed via
direct curl during the investigation) — the app kept no record of what was
actually requested, what came back, or whether an attempt was a fresh
network call or a cached miss being replayed, so the cause could only be
narrowed to "probably a stale cached negative result," not confirmed.

New table `aircraft_event_log` (`AppDatabase.kt`, `MIGRATION_7_8`, version
7→8): one row per `DETECTED` / `ENRICHMENT_ATTEMPT` / `MOVED_TO_HISTORY`
event, keyed by icao, with `source`, `requestKey`, `requestUrl`,
`servedFromCache`, `success`, `resultSummary`, `durationMs`. Purged by the
existing hourly 7-day-cutoff loop in `PipelineService.kt` alongside
`aircraft_history`/`aircraft_seen` — no new scheduling.

Logging hooks:
- `onAircraftUpdated` logs one `DETECTED` row per icao per process lifetime
  (`state.firstSeenMs`), tracked via a `ConcurrentHashMap.newKeySet<String>()`
  the same way `metaLookupInFlight`/`routeLookupInFlight` already are.
- `recordDeparted` logs `MOVED_TO_HISTORY` at actual eviction wall-clock
  time — a fact not previously captured anywhere (`lastSeenMs` is
  last-message time, not eviction time).
- All 5 real fetch sites (`AircraftMetaEnrichment`: hexdb, OpenSky,
  adsbdb-aircraft; `RouteEnrichment`: adsbdb-route; `FlightAwareEnrichment`:
  flightaware) log `ENRICHMENT_ATTEMPT` with the exact URL, timing, and a
  short result summary — including the cache-hit path, so a report now
  distinguishes "just checked live and found nothing" from "replaying a
  result cached before this session's fixes landed," which is precisely
  the ambiguity that stalled the C027D7 investigation.
- `RouteEnrichment` previously had **zero** logging of any kind (confirmed
  by grep before this change) — this closes that gap, not just adds to it.
  `lookupRoute` gained an `icao` parameter (was callsign-only) purely so
  the audit rows can be keyed correctly.

Manual retry: `PipelineService.retryEnrichment(icao)` clears that icao's
`aircraft_meta_cache` and `enrichment_cache` rows (new DAO
`deleteByIcao`/`deleteByKey` queries) and `FlightAwareEnrichment`'s
in-memory state for whatever ident that icao currently maps to (new
`clearForIcao`), then immediately re-invokes `maybeEnrichMeta`/
`maybeEnrichRoute`/`maybeEnrichFa` — scoped to one aircraft, replacing the
"clear all app data and hope" workaround the C027D7 investigation had to
fall back on.

UI: `AircraftDetailSheet.kt` gained a collapsible "ENRICHMENT LOG" section
(same collapsed-by-default pattern as the existing message timeline) with
a "Retry" button, backed by a new `PipelineService.eventLogFor(icao)`
suspend query threaded through `MainActivity`'s existing bound-service
callback pattern. Only wired into the live-aircraft sheet — this app has
no detail view at all for a departed/History aircraft yet, so the log is
visible only while an aircraft is still live; adding a History detail
sheet is a separate, larger piece of work, not part of this change.

Two enrichment ideas raised but deliberately not built this round: dropping
OpenSky's aircraft-metadata endpoint from the source rotation (confirmed
gone, 410, permanently — every meta lookup still queries and always fails
it); and a bundled offline ICAO→registration/type database generated from
OpenSky's bulk aircraft dump, which would resolve most aircraft instantly
without depending on any of the 3 live sources being up.

`:core:receiver:test` + `:app:testDebugUnitTest` pass —
`AppDatabaseMigrationTests.kt`'s 4 fixture builders needed `MIGRATION_7_8`
added to their `addMigrations(...)` calls (they build against
`AppDatabase::class.java`, which now targets version 8; without it Room
had no 1→8 path and every test in the file failed with "migration
required but not found"). `:app:assembleDebug` passes. Version bumped to
v2.0.4 (`versionCode` 36), debug APK built and added to `dist/`.

Not verified on-device — no physical device is reachable from this
sandbox. Avi should confirm on the phone: open a live aircraft's detail
sheet, expand ENRICHMENT LOG, confirm DETECTED appears and
ENRICHMENT_ATTEMPT rows accumulate per source with real URLs/results; hit
Retry and confirm fresh rows appear a couple seconds later; let an
aircraft depart and confirm MOVED_TO_HISTORY logs.

## 50. Enrichment log export/share — the fix for §49's "vanishes once departed" gap (2026-08-14, v2.0.5)

§49 shipped the log but left it reachable only from the live aircraft
detail sheet — once an aircraft moved to History there was no UI path to
its rows at all, and no way to get data out of the app for analysis. Avi
flagged both directly. The underlying data was never actually gated on
live/departed status (`aircraft_event_log` is keyed by icao only, purged
on the same 7-day loop as everything else) — the gap was UI reach, not
retention.

Fix, not a new view: `HistoryScreen.kt` — the screen Avi already lands on
once an aircraft has departed — gets a third top-row button, "Share log",
next to the existing "Share"/"Clear". Always enabled, unlike the other two
(not gated on `entries.isNotEmpty()`), since the event log is a separate
table from `aircraft_seen` and can have data even when History is empty.
Exports the **entire** table across every icao ever logged, live or
departed, not filtered to one aircraft — Avi's ask was "so we can analyze
them," which wants the full dataset in one file, not a per-aircraft
walk.

New `CsvExporter.exportEventLog()` (same file-under-external-storage +
share-sheet pattern as the pre-existing `exportHistory`/`exportAircraftSeen`),
backed by a new `AircraftEventLogDao.getAll()` query (`ORDER BY timestampMs
DESC`) and `PipelineService.exportEventLogCsv()`, mirroring
`exportHistoryCsv()` exactly. `MainActivity`'s `shareCsv()` helper gained an
optional `chooserTitle` param (was hardcoded "Share history") so the two
exports get distinct share-sheet titles instead of both saying "Share
history."

No new History-detail-per-aircraft UI was built — that would have been the
alternative fix (tap a departed aircraft, see its own log inline) but is a
substantially bigger change (a whole new detail sheet for non-live
aircraft) for the same practical outcome Avi asked for: get the data out
where it can be read. Worth revisiting if per-aircraft in-app browsing
after departure turns out to matter more than the CSV round-trip.

`:core:receiver:test` + `:app:testDebugUnitTest` pass, `:app:assembleDebug`
passes. Version bumped to v2.0.5 (`versionCode` 37), debug APK built and
added to `dist/`. Not verified on-device — Avi should confirm the "Share
log" button in History produces a CSV with rows for aircraft no longer
live.

**Also folded into v2.0.5 (same version, follow-up commit):** range-ring
segment count doubled, 72 → 144 (`AircraftMapLayer.kt`'s `circlePoints()`
default) — visibly faceted at close zoom before, per Avi. Capped at 144
rather than the discussed 180 ceiling since 2× the prior value already
lands under it. Kept as the existing geodesic-polygon approach (not
MapLibre's `CircleLayer`) — a true GPU circle was discussed as the
zero-faceting alternative (pixel radius from a zoom-exponential
expression) but is a larger restructuring (rings move from line features
to point features, label placement logic changes) for a difference that
likely isn't visible at this radii/zoom range once faceted at 144 points
instead of 72. Revisit only if faceting is still visible on-device at max
zoom. `dist/adsb-android-v2.0.5.apk` rebuilt in place — `versionCode`/
`versionName` deliberately left at 37/2.0.5 per Avi's instruction rather
than bumped to a new version.

## 51. Two real bugs found by reading a real exported enrichment log (2026-08-14, v2.0.6)

Avi exported the log via §50's new button and asked why FlightAware showed
repeated requests. Parsed the actual export (14,494 rows) rather than
guessing — found two distinct, confirmed bugs.

**Bug A — cache-hit checks were being logged as if they were new attempts,
on every single position update.** 14,333 of 14,456 `ENRICHMENT_ATTEMPT`
rows (99.1%) had `servedFromCache=true`; only 123 were real network
fetches. One icao (`C02129`) alone produced 4,195 rows, averaging 10.7
rows/second while live. Root cause: `onAircraftUpdated()` fires on every
merged ADS-B message and calls `maybeEnrichMeta`/`maybeEnrichRoute`/
`maybeEnrichFa` every time; the actual network fetch is correctly
rate-limited/TTL'd, but each class's cache-hit branch logged
unconditionally on every call, with no dedup on "already logged this exact
cache hit a moment ago." Fixed by tracking, per class, whether the
*current* cached value has already been logged once:
- `AircraftMetaEnrichment`/`RouteEnrichment`: `ConcurrentHashMap<String,
  Long>` mapping key → the `cachedAtMs` already logged;
  `map.put(key, cachedAtMs) != cachedAtMs` both updates and detects "new
  value, log it" atomically. Self-correcting on cache refresh (a new
  `cachedAtMs` naturally differs) — no explicit clearing needed.
- `FlightAwareEnrichment`: no timestamp on its in-memory cache, so dedup is
  a `HashSet<String>` of idents already logged for their *current* cached
  result, guarded by the existing `lock`. Cleared on ident upgrade
  (`clearForIcao`'s existing invalidation block), on `fire()` writing a
  fresh value, and in `clearForIcao` itself — so a manual retry or a
  genuine new fetch correctly re-arms one fresh log line.

**Bug B — every real (non-cached) `adsbdb-route` fetch was failing.** All
10 fresh attempts in the export failed with the same error: `Serializer
for class 'AdsbdbCallsignResponse' is not found.` Root cause, confirmed
live by curling `api.adsbdb.com/v0/callsign/...` with the exact callsigns
from the failing rows: adsbdb's `response` field is an **object**
(`{"flightroute": {...}}`) for a callsign it recognizes, but a plain
**string** (`"unknown callsign"`, `"invalid callsign: X"`) for one it
doesn't — confirmed against `C02129` (an ICAO hex mistakenly tried as a
callsign) and a made-up callsign, both live. The fixed object-shaped
`@Serializable` class (`AdsbdbCallsignResponse` → `AdsbdbResponseBody` →
`AdsbdbFlightRoute` → `AdsbdbAirport`) could only decode the object case —
every miss (i.e. most real-world callsigns) threw instead of resolving to
"no route." Since routes were always cached negative right after failing,
this looked identical to "adsbdb genuinely has no route for this
aircraft," when in fact the lookup was silently broken the entire time.

Fix: replaced the `@Serializable`-class decode with manual `JsonElement`
parsing (`RouteEnrichment.kt`'s new top-level `parseAdsbdbRoute()`),
matching the pattern `FlightAwareEnrichment.parse()` already uses — a safe
`as? JsonObject` cast on `response` means a string response is just "no
route" instead of a decode exception, and the code is now defensive
against adsbdb changing shape again. The 4 now-unused `@Serializable` data
classes were deleted; `RouteEnrichment`'s `HttpClient` no longer needs
`ContentNegotiation` at all — fetches raw text and parses it directly,
same as FlightAware's scrape path. Also added the missing `UserAgent`
header and a `requestTimeout`, matching `AircraftMetaEnrichment`'s already
proven-working client config.

New `RouteEnrichmentTests.kt`: `parseAdsbdbRoute()` against the exact real
JSON captured live during this investigation (a working AAL123 route, the
`C02129` "unknown callsign" case, an "invalid callsign" case, a null
`flightroute`, and malformed JSON) — locks in both the fix and the exact
failure mode that caused it.

`:core:receiver:test` + `:app:testDebugUnitTest` pass, `:app:assembleDebug`
passes. Version bumped to v2.0.6 (`versionCode` 38), debug APK built and
added to `dist/`. Not verified on-device — Avi should confirm route
lookups now actually succeed for a recognized callsign, and that the
enrichment log for a long-lived aircraft stays small instead of growing
every second.
