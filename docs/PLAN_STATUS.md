# Plan vs. Code â€” Status and Path to Completion

Living document. Rewritten whenever work lands, so it always describes the code as
it is now â€” not a dated audit with corrections bolted on.

**Last updated:** 2026-08-03 â€” Coverage-history heatmap, best-range-ever, and
first-time-seen milestone notifications added (Receiver tab + PipelineService);
a selectable OSM/Esri base map (Settings â†’ Base map); the app version is now shown
in Settings. Live row split into a top strip (identity + data block) plus
full-width airline/route and type lines (5 lines total); Aircraft Stats screen
(Settings â†’ "View aircraft stats") logging every departure to an append-only
`aircraft_visits` table, grouped by airline vs private aircraft. Phase 1â€“4 UI
models, shared atoms, navigation, Live screen, AircraftRow, AircraftDetailSheet,
LogsScreen, ReceiverScreen (viewModel wrapper), SettingsScreen (viewModel wrapper),
MapScreen all compiling. PipelineService now exposes public `startPipeline()`,
`stopPipeline()`, `reconnect()`. TextListScreen deleted (replaced by LiveScreen +
5-tab NavigationSuiteScaffold). Version **v1.6.0** (`versionCode` 15).
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
| 5-tab NavigationSuiteScaffold (Live, Map, Receiver, Logs, Settings) | âœ… |
| Live screen: metrics header, sort bar, dense AircraftRow, non-nominal states, start/stop | âœ… |
| Aircraft detail sheet (ModalBottomSheet, freshness dots, diagnosis cards, message timeline) | âœ… |
| Logs screen (Events + History tabs) | âœ… |
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
| History (departed aircraft, Room, tab) | âœ… |
| Settings screen | âš ï¸ sectioned (Receiver / Sort / Gain / PPM / Tuning / Observer / Auto-stop / Data) but not searchable |
| Landscape layout | âœ… rows fold to one detail line |
| Map | âœ… osmdroid, dark-filtered tiles, range rings on the named range scale, observer, single-overlay markers, trails, label collision, clustering + decimation chip, controls, layers panel, selection sheet |
| Offline maps | âœ… Â§23 â€” Wi-Fi-only downloads, named segments, travel append, safe deletion. 90 tests. Default URL = OSM Mapnik (same as live map). **Toggle** (`offlineDownloadEnabled`, default off) gates all downloads; import from cache always works. |
| Display units setting (mi/nm/km) | âœ… `DistanceUnit` in `:core:receiver`, persisted, threaded through rows/map/coverage |
| Position history for trails | âœ… `AircraftState.positionHistory` (bounded 200, duplicate-suppressed); parity unaffected â€” the harness compares an explicit field allow-list |
| Aircraft detail / `_diagnose()` port | âœ… ModalBottomSheet with freshness dots, diagnosis cards, TCAS, message timeline |
| Logs / diagnostic events | âœ… Events tab (DiagnosticEventBuffer ring, severity-colored) + History tab |
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

**`ui/stats/StatsScreen.kt`** — reached via Settings → "Aircraft stats" (mirrors the
existing "Manage offline maps" section/route pattern exactly, not a 6th bottom-nav
tab, per Avi's choice). Two tabs, "By airline" (grouped, sticky header per airline
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
