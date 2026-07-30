# Phase Progress

## Phase 0 - Skeleton: DONE, tested (25 JVM tests pass)
## Phase 1 - Core decoder: DONE, tested (52 tests: CRC, DF/TC dispatch, CPR global, altitude/velocity/squawk, demod LUT+preamble, NetworkSource AVR parse, AircraftManager)

## Phase 2 - Enrichment: IN PROGRESS

### Session 21 (FAA removal, USB reconnect rebuild, UI fixes; v0.7.0 → v0.8.0): DONE, tested

**v0.7.0 — FAA database removed.** `FaaDatabase.kt` deleted, `faa_aircraft` Room
table dropped (`MIGRATION_4_5`, DB v4→v5), DI and `PipelineService` wiring
stripped. It was never used and never generated in the Python reference either —
the table is created lazily on first lookup there, which never ran.

**v0.8.0 — USB disconnect detection and auto-reconnect rebuilt.** Reconnect after
unplug/replug did not resume. Two structural causes, three failed fixes before
either was found:

- `NetworkSource` opened the IQ socket with `soTimeout = 0`, so a driver holding
  the socket open with no device behind it parked `read()` forever — no EOF, no
  exception, state stuck on Running. **This is the direct descendant of the
  "Known bug fixed this session" note at the bottom of this file:** an earlier
  session removed the 10 s timeout to stop AVR-mode hiccups killing the loop.
  Correct for AVR text (lines arrive minutes apart), wrong for the IQ stream
  (a block every ~65 ms), and the two shared one class. Now a constructor
  parameter — `0` retained for AVR, `IQ_READ_TIMEOUT_MS = 3000` for IQ.
- Recovery fired exactly once, 2 s after failure. If the dongle wasn't back by
  then the receiver settled into Error permanently. Replaced with a retry loop
  (capped exponential backoff, `ReconnectPolicy`) gated on `UsbPresence` —
  `UsbManager.getDeviceList()`, the authoritative API, which the manifest already
  permitted and no code had ever called.
- `tryConnectExisting` now requires real sample flow; the `RTL0` greeting is
  replayed from cached device info even after the dongle is yanked, so accepting
  it reused a dead session *and* skipped the `iqsrc://` intent that would have
  reopened the device. `UsbReconnectGuard` (added and tested during a failed
  attempt) deleted — measuring staleness beats inferring it.

Confirmed on hardware by Avi. Research into the rtl_tcp ecosystem confirmed the
protocol is strictly one-way — no ACK, no readback, no status, even in the
extended librtlsdr fork whose query-named commands print server-side only. Two
findings logged for later: the driver returns a `supportedTcpCommands` capability
array that `SdrSourceActivity` discards, and no `SDR_DEVICE_DETACHED` broadcast
appears to exist in the driver at all.

**UI/behaviour.** Single short error string across every USB failure path, shown
as a dismissible banner on Live; message-history timestamp no longer wraps;
system back from aircraft detail returns to Live instead of minimizing; switching
auto/manual gain mode now restarts the pipeline (`ConfigChange` and `CLAUDE.md`
updated — previously PPM was the only restart trigger).

310 core:receiver + 32 app tests, 0 failures. Exported to
`adsb-android-v0.8.0-20260727/`, verified to build standalone.

### Session 20 (aircraft detail screen, v0.6.0): DONE

Tap any aircraft row → `AircraftDetailScreen`. Portrait-optimized, four scrollable
cards: Identity (ICAO, callsign/reg/operator/route/squawk with provenance glyphs),
Diagnosis (callsign/altitude/position/speed/TCAS — explains why each is missing),
Signal & Reception (dBFS last/avg, valid/corrected/bad counts, distance/bearing),
Message History (last 50 frames newest-first: timestamp, DF/TC, signal, CRC result).
Navigation: `detailIcao` state in `MainActivity`, same pattern as Settings.

### Session 19 (second formula-audit fix round, items #7-#19): DONE, tested

Completed all 13 remaining correction_plan.md items (#7-#19) in one pass, using
the Python reference as source of truth. Changes span `MessageDecoder`,
`AircraftState`, `AircraftManager`, `Demodulator`, `RawFrame`,
`ReceiverRepository`, and `PipelineService`.

- **#7** TC31 NACv — added missing `(me[4]>>3)&0x07` decode
- **#8** DF4 flight-status ground flag — `fs=(data[0]>>2)&7`, `onGround=fs in (1,3,5)`; rewrote 2 tests
- **#9** DF16 onGround — `LongAirAir` now carries `onGround=false`
- **#11** TC1-4 emitter_category — `(tc-1)*8 + (me[0]&7)`, was never written
- **#12** CPR tie-break — `>=` → strict `>`, ties now pick even (matching Python)
- **#13** CPR rounding — lat/lon rounded to 6 decimals in mergeAdsb
- **#14** CPR state purge — `purgeCpr(icao)` called from expireStale
- **#15** II codes as set — `interrogatorIds: Set<Int>` accumulated, rendered as `"3;7"`
- **#16** Signal averaging — `signalHistory` deque(20) with `avgSignal` computed property
- **#17** Per-aircraft message history — `messageHistory` deque(50) with `MessageSummary`
- **#18** Per-aircraft CRC counters — `validCount`/`correctedCount`/`badCrcCount`/`lastPositionMs`
- **#19** Frame timestamp — `RawFrame.sampleOffset` set by demodulator, per-frame ms in PipelineService

302 core:receiver + 31 app tests, 0 failures, APK builds. 20 of 29 findings
now fixed; 9 remain (counters #20-22, Android-only behaviours #23-29).

### Session 18 (Python<->Android formula audit + first fix round): DONE, tested

Added `docs/correction_plan.md` (29 findings, each verified against the live
Python reference) to PLAN_STATUS.md as Phase 1.5. Fixed 7 of the 29:

- **#1 DF11 II code** - was raw low nibble of the PI byte (no XOR/CRC at all,
  wrong on 18,764/20,000 sampled values); now `(addr>>20)&0xF` where addr is
  the already-existing `CrcChecker.computeCrc` value. Found empirically that
  ii is structurally always 0 for any DF11 the reference's own CRC gate
  accepts - a genuine nonzero ii produces an address far above the gate's
  threshold and gets rejected as BAD before decoding runs. Verified by
  constructing frames with real Python execution, not assumed.
- **#2 TC20-22 GNSS altitude** - was piped through the barometric Gillham
  decode and overwrote `altitudeFt`; now its own 25-ft-step formula into a
  new `altitudeGnssFt` field, kept separate.
- **#5/#6 TC31 NACp/SIL** - were read from the wrong bytes entirely (`me[5]`/
  `me[6]` instead of `me[3]`/`me[4]`), and SIL had a subtype branch the
  reference doesn't have. Fixed to the reference's exact bytes, unconditional.
- **#10 sticky ground flag** - `onGround` only ever got set true (by surface
  messages), never cleared by an airborne report, so a departing aircraft
  stayed "on ground" forever. `AdsbFields.onGround` changed non-nullable
  `Boolean` -> nullable, merge now direct-assigns whenever a TC reports it.
- **#3/#4 TC19 velocity fields** - airspeed and magnetic heading were written
  into `groundSpeedKt`/`trackDeg` (ground-speed/track's own fields). New
  `airspeedKt`/`headingDeg`/`speedType` fields, kept distinct from the
  *existing* Comm-B-sourced `trueAirspeedKt`/`magneticHeadingDeg` (different
  message family - reusing those would trade one conflation bug for another).

Rewrote the 2 pre-existing tests that had asserted the old wrong DF11 formula
rather than deleting them. 302 core:receiver tests (up from 300), 0 failures,
both golden parity suites unaffected (their `comparedFields` doesn't check
any of the touched fields today - a separate, undone gap, not something this
round closed, since wiring it in risks surfacing unrelated known divergences
like the interrogator_ids set-vs-scalar difference).

Deployed to the Pixel 6 after: live decode through every modified path ran
clean for the observation window, no crash. Full writeup: `docs/PLAN_STATUS.md`
S12.

### Session 17 (Phase 5: coverage + performance metrics, backend + CSV only): DONE, tested

Ported `observability/performance.py` and `observability/coverage.py` into
`:core:receiver` (`PerformanceMetrics.kt`, `CoverageMetrics.kt`, pure and
JVM-tested) plus `:app` file-writing wrappers (`PerformanceCsvLogger`,
`CoverageCsvLogger`) matching `RawMessageLogger`'s existing day-rotating-file
pattern. By decision: no `CoverageCard`/`RateChart` UI yet - that's Phase 4's
Receiver-screen rebuild, not built today.

Found and fixed a real counting-policy gap while porting: `PipelineStats`
folded RECOVERED frames into `validMessages` with no way to isolate them, and
folded genuinely-bad CRCs together with parity-address frames in
`invalidMessages` - Python counts those as three separate things. Added two
new counters (`recoveredMessages`, `badCrcMessages`), additive only, the four
existing UI-facing counters are unchanged.

Coverage reuses `AircraftManager`'s already-computed `distanceNm`/`bearingDeg`
(same haversine formula/radius as `enrich/distance.py`) instead of
recomputing distance/bearing - pure aggregation over values already correct.

Caught a real rounding divergence empirically: `_symmetry_score`'s Python
`round()` is round-half-to-even; a naive Kotlin `Math.round()` disagrees at an
exact `.5` tie, which running the real Python function showed happens on a
common case (reception in exactly 1 of 8 sectors = 12.5, rounds to 12 in
Python vs 13 under round-half-up). Fixed with `Math.rint()`.

FlightAware counters are dropped (matches the already-decided FA drop) - the
3 `fa_queries_*` CSV columns stay for name/order parity, always 0.

No full CSV column-diff harness built (would column-diff live Python vs live
Android over identical input - bigger task than asked for). Instead every
formula individually verified against the actual Python reference executed
directly, not hand-derived.

Deployed to the Pixel 6 mid-session: both `performance_2026-07-26.csv` and
`coverage_2026-07-26.csv` appeared in app-private storage with correct
headers and live, plausible values (e.g. 1,139 msgs/60s, 40.0% decode
success, `high_noise` hint correctly fired). No crash.

300 core:receiver + 31 app tests (up from 267 + 29), 0 failures, parity
unaffected. Files have no in-app export entry point yet - that's the
remaining Step 8 item.

### Session 16 (Step 3: module rename): DONE, tested

`:core-test` -> `:core:receiver`. Moved `core-test/src` to `core/receiver/src`
(package names unchanged), updated `settings.gradle.kts` and
`app/build.gradle.kts` dependency, fixed path references in three `tools/*.py`
scripts and one comment in `UsbRtlSdrSource.kt`, deleted the three now-obsolete
mitigation artifacts (`core-test/README.md`, its build-file header comment, the
`settings.gradle.kts` note) that existed only to contradict the misleading name.

Deliberately did not extract `:core:model` alongside it - no second consumer
exists yet (`:core:ui`/`:data` are Phase 4/5, not built), so splitting now would
be modularising for a consumer that isn't there.

267 core-test + 29 app tests, 0 failures, identical counts to before the move -
`GoldenFrameParityTests` (26,760 frames) and `GoldenStateParityTests` (3,484
rows) both green, confirming the rename changed no behaviour.

### Session 15 (backlog steps 1-5: ReceiverRepository, Room migrations, signal level, DF16/TCAS, bias-tee): DONE, tested; Settings UI walk pending

**1. ReceiverRepository + back-pressure.** Extracted out of `PipelineService`
into `:core-test` (`aircraft/ReceiverRepository.kt`): owns the aircraft table,
4Hz publish tick, expiry, and a bounded `Channel` ingest queue (`DROP_OLDEST`,
capacity 64) with a drop counter via the channel's own `onUndeliveredElement`.
9 new tests including a deterministic overflow test. Surfaced on the Receiver
tab as "Dropped (backpressure)" - reads 0 on hardware by design (no real
backlog at current frame rates). Found and fixed a real compile gap while
wiring it in: `setLookup()` had no path onto the new repository.

**2. Room migrations**, replacing `fallbackToDestructiveMigration()`.
`MIGRATION_1_2` (creates `aircraft_seen`) and `MIGRATION_2_3` (squawk
INTEGER->TEXT, old values dropped not stringified). Verified with Robolectric +
`room-testing` against a byte-correct v1 database built via Room itself, not
hand-transcribed SQL.

**3. `signalDbfs` populated on the USB path.** Root cause: `PipelineService`
discarded the demodulator's real per-frame signal level and rebuilt a bare
`RawFrame` with the 0.0 default before CRC checking. One-line fix, plus a
display-format fix (was rendering "0%" even once populated). Confirmed on
hardware: real percentages (5-21% observed).

**4. `_resolve_ap_icao` ported.** Discovered the entire DF16 (TCAS long air-air
surveillance) decode path had never been implemented - implemented SL,
altitude, BDS 3,0 RA decode, and the AP-field intruder heuristic, plus DF0's
onGround bit. `_resolve_ap_icao` itself proven dead code in the Python
reference (XOR involution makes its `len(matches)==1` check unreachable -
verified against the live Python source, 0 hits in 200,000 trials). Ported
bug-for-bug rather than fixed, documented in code and tests.

**5. Bias-tee.** rtl_tcp `set_bias_tee` command `0x0e`, same struct as the
existing gain commands. Live over the control channel via
`UsbRtlSdrSource.applyBiasTee()`; `AppConfig.biasTee` (default off),
`ConfigChange.requiresBiasTeeReapply`, Settings toggle with a hardware-risk
warning. New ground relative to the Python reference (which uses pyrtlsdr's
direct call, not rtl_tcp's wire protocol) - command encoding is unit-tested,
not verified against a live dongle.

267 core-test + 29 app tests (up from 240 + 23), 0 failures, parity unaffected.
Deployed and verified on the Pixel 6 after steps 1-4. Step 5's install was
confirmed clean, but a live call on the phone at deploy time meant the Settings
screen itself was not walked in the browser/UI this session - per standing
instruction, handed to Avi rather than looped on remotely.


### Session 14 (Step 4 continued - accept-rate window + Receiver tab): DONE, tested; visual check pending

**Rolling accept-rate window** in `PipelineStats`: tested/accepted/rejected over
the last 5-60s (default 10s), a ring buffer of per-second deltas summed each 1Hz
tick. This is the readout that would have shown Session 7's failure immediately -
tested climbing into the millions while accepted stays at zero, rather than
looking like a plain high number on screen. Tested with a case shaped exactly
like that incident (50,000 tested, 0 accepted).

**New Receiver tab** (`ui/receiver/ReceiverScreen.kt`) - third tab alongside
Live/History, since there's no adaptive nav shell yet to hang a new destination
off (that's Step 5). Status/tuner line, the accept-rate panel with guidance text
that never claims more than the evidence supports, pipeline counters, and current
tuning values with a link back to Settings -> Tuner rather than duplicating the
sliders there.

240 core-test + 23 app tests (7 new), 0 failures, parity unaffected (26,760
frames / 3,484 rows still match Python). Deployed to the Pixel 6 - logcat
confirms clean start. **Visual confirmation of the new tab is outstanding**: the
phone was locked behind fingerprint auth at deploy time; per standing instruction
this was handed to Avi rather than looped on remotely.


### Session 13 (Step 4 continued - fixed-rate publish + thread-safety fix): DONE, tested

Finished the repository-layer piece of Step 4 left open at the end of Session 12.

**Fixed-rate publish.** `PipelineService` was sorting and publishing the aircraft
list on every decoded message — up to ~870/s on Session 7's own hardware capture,
against a UI that cannot render faster than a few Hz. New
`startAircraftPublishLoop()` ticks at 4 Hz and only emits when the sorted list
differs (structural `equals()` on `AircraftState`/`List`, no new diff type). The
per-message `publishAircraft()` calls are gone; the one exception kept is an
explicit sort-order change in Settings, which republishes immediately rather than
waiting up to 250 ms.

**Thread-safety fix, found while wiring the ticker in.** `AircraftManager`'s doc
comment already claimed it needed external synchronization, but nothing enforced
it: the message path and `startExpiryLoop()` ran as separate coroutines on
`Dispatchers.Default` (a multi-threaded pool), so they could already race on the
same `LinkedHashMap`. Adding the ticker as a third unsynchronized accessor would
have made it worse. Fixed with `Dispatchers.Default.limitedParallelism(1)`,
confining every touch of `aircraftMgr` (message path, expiry, route-enrichment
callback, restart reset, ticker) to one sequential dispatcher.

**Deliberately not built:** a standalone `ReceiverRepository` class (logic stays
in `PipelineService` for now) and a formal back-pressure/drop-counter channel —
frames are still processed synchronously with no async queue, so there is
nothing yet that could actually back up.

240 core-test + 16 app tests, 0 failures. Frame/state parity unaffected (26,760
frames / 3,484 rows still match Python). Deployed and verified on the Pixel 6:
live list updates normally, History persists across restart, counters climb
continuously, no crash.


### Session 12 (Step 4 started - sort order moved out of AircraftManager): DONE, tested

**By decision:** sort-by lives in Settings for this version (not the Live/History
screens yet); default is first-seen, all other orders selectable.

`AircraftManager.aircraft` returned a hardcoded nearest-first order before this,
recomputed by a full sort on every decoded message — a presentation choice baked
into domain state, and precisely what Step 4's repository layer exists to remove.
Split in two:

- `AircraftManager.aircraft` now returns **first-seen order** (free, from
  `LinkedHashMap` not reordering a key on re-`put`).
- New `core-test/.../aircraft/AircraftSort.kt`: pure function, six
  `AircraftSortOrder` values (`FIRST_SEEN` default, `NEAREST`, `ALTITUDE`,
  `CALLSIGN`, `MESSAGE_COUNT`, `LAST_SEEN`), applied to a snapshot list.

`AppConfig.sortOrder` persisted via DataStore; `PipelineService.publishAircraft()`
applies it once at emission time (not per-message), and re-publishes immediately
on a sort-order change so the new order is visible without waiting for the next
frame. Verified sort changes trigger no restart, no gain reapply, no demod retune.

Two existing tests had baked in the old nearest-first behaviour directly against
`AircraftManager.aircraft` and had to be rewritten against the new contract
(`Phase1ExtendedTests`, `Phase2Tests`) — same shape of gap as before: the
component was tested, the decision to bake a presentation concern into it was not
questioned until now.

240 core-test + 16 app tests, 0 failures. Deployed to the Pixel 6, verified on
screen: all six options render in a new "Sort aircraft by" section, "First seen"
selected by default. Frame and state parity unaffected (26,760 frames / 3,484
rows still match Python).


### Session 11 (Steps 1-2 verified, state parity, `:core-test` documented): DONE, tested

**Step 1 (single source) verified.** Non-antenna sources are gone from production;
`NetworkSource` remains only as the loopback transport the driver app hands IQ
over. `SingleSourceGuardTests` walks the shipped source tree so the code cannot
return quietly — it needed a fix to compile (literal newlines inside a string).
Demod tuning knobs (`preambleGapDivisor`, `deltaFloor`) are now `AppConfig` fields
applied live, closing the Phase 3 gap that left the only RF controls unreachable.

**Step 2 (state parity) done.** `GoldenStateParityTests` replays a capture through
CRC -> decode -> AircraftManager on a virtual clock and diffs the aircraft table at
every 10 s checkpoint: **3,481 rows across 266 checkpoints match**. Running it at
all required removing three wall-clock reads (`AircraftManager.update`,
`MessageDecoder.decode`, the CPR frame stamp) — which is exactly why the
accumulated behaviour had never been testable.

Two real bugs it found, both invisible to frame-level tests:
- **CPR pair ageing used the wrong comparison.** Kotlin accepted a pair when the
  two frames were within 10 s *of each other*; the reference requires both within
  10 s *of now*. A minutes-stale even frame paired with a fresh odd one decoded as
  a current position — one aircraft landed ~500 km off, in Arizona.
- **CPR read the wall clock**, so on replay every pair looked simultaneous and the
  age check could never reject anything.

**Second reference bug found.** `decode_airborne_velocity` reads the TC19 subtype
as `(me[0] >> 1) & 7` instead of `me[0] & 7`, so every subsonic ground-speed
message (subtype 1 — essentially all real traffic) is decoded as an airspeed
message. In the capture the reference populates `ground_speed_kts`/`track_deg`
**0 times in 3,481 rows** while `heading_deg` carries 2,574 values derived from the
E/W velocity bits. Kotlin reads it correctly; allow-listed, not matched. Python
untouched.

**`:core-test` naming documented, rename deferred.** Step 3 (rename to
`:core:receiver`, extract `:core:model`) is deferred by Avi's decision in favour of
user-facing work. Because the name actively misleads — it holds the entire
production receiver — the contradiction is now stated everywhere someone meets it:
`core-test/README.md`, a header comment in `core-test/build.gradle.kts`, and a note
at the `include` in `settings.gradle.kts`. Each explains what the module really
contains, how the name happened, and that it is what let a stale stub keep two
green tests asserting the 2.4 Msps sample-rate bug.

`docs/PLAN_STATUS.md` gains a **"To be done in the future"** section holding the
deferral with its reminder. `CLAUDE.md` gains a standing rule: update
`PLAN_STATUS.md` in the same turn as any code change, rewriting affected sections
rather than appending contradictory corrections.

232 core-test + 14 app tests, 0 failures. Deployed and verified on hardware.


### Session 10 (Python-parity decode fixes + golden replay harness): DONE, tested

**Cause of all four bugs below: dump1090 was used as the implementation
reference instead of the Python receiver.** dump1090 packs squawk digits into a
hex-coded integer meant to be printed with `%x`, and routes Gillham altitude
through that same packing. Python returns formatted values directly. The two
conventions met at the UI boundary and produced garbage no unit test could see.

- **Squawk** — `decodeId13Field` (dump1090 hex-pack) replaced by `decodeIdentity`,
  a port of `_decode_identity`, returning the 4-digit octal *string*. Squawk is
  now `String?` through `DecodedMessage`, `AircraftState`, Room (DB v3) and the
  UI, so it cannot be re-rendered with the wrong radix. On-screen squawk 6272 was
  displaying as "61162".
- **Altitude** — `decodeAc12Field` / `decodeAc13Field` are now ports of
  `decode_altitude_12bit` / `decode_altitude_13bit` / `_decode_gillham`.
  `modeAToModeC` (dump1090) deleted.
- **TC23 / TC28 squawk** — removed. The reference's `_decode_me` does nothing for
  these (`elif tc == 28: pass`); the dump1090 paths were inventing a squawk on
  731 frames of the golden capture where the reference reports none.
- **Aircraft expiry** (Session 9) and **History UI** shipped in the same build.

**Golden replay harness** (`GoldenFrameParityTests`) — replays the Phase 0
fixtures through the Kotlin pipeline and diffs ICAO, callsign, altitude and
squawk against Python frame by frame. This is what found TC23/TC28 and the
Comm-B callsign; the goldens had been captured in Session 8 and never diffed.

Result: **26,512 / 235 / 13 frames match Python exactly.** One allow-listed
divergence: DF20/21 Comm-B BDS 2,0 callsign, which the reference never reads —
additive, requested in Session 5, and asserted narrowly so any callsign
divergence on a DF the reference *does* decode still fails.

Two vacuous tests removed: `assertTrue(squawk >= 0)` (true for every possible
return) and the `isOpen` stub assertion.

**Landscape** — no orientation lock existed in the manifest; rotation was a
device-level state. The row layout did not adapt, though: four text lines in
~376 dp of height showed barely two aircraft. Rows now fold to a single detail
line in landscape.

227 core-test + 13 app tests, 0 failures. Verified on hardware in landscape.

### Session 9 (Aircraft expiry never ran): DONE, tested

`AircraftManager.expireStale()` was written in Phase 1 and had two passing unit
tests, but **no caller ever invoked it**. The live list therefore accumulated every
ICAO heard since launch: "aircraft tracked" was a session total, and stale rows
stayed on screen indefinitely. The hourly loop in `onCreate` purges the Room
history table, not the live table — easy to mistake for expiry when reading the
service.

`expirySeconds` was also a constructor val and `AircraftManager()` was built with
the default, so `AppConfig.aircraftExpirySeconds` never reached it — the Settings
field did nothing. Now a var, applied on each pass.

Fixed: `PipelineService.startExpiryLoop()`, cadence mirroring the Python reference
(`core.py:_run_purge`: quarter of the window, capped at 15 s), re-publishing
`_aircraft` only when something was actually removed.

Tests added for the runtime-changeable window and for the unbounded-growth
regression itself — the gap was that the function was tested while its wiring was
not. 230 tests (217 core-test + 13 app), 0 failures.

**History UI built.** New `aircraft_seen` table (DB v2), one row per aircraft
keyed by ICAO holding its last known state, written when the aircraft expires.

Deliberately *not* built on the existing `aircraft_history` table: that is a
position track log sampled every 30 s and gated on `latitude != null`, so any
aircraft heard only as a bare Mode S reply would never appear. `expireStale()`
now returns the departed states rather than a count, because expiry is the last
moment those values exist.

- `AircraftSeenEntity` / `AircraftSeenDao` — `observeAll()` Flow, 7-day purge on
  the existing hourly schedule, `clear()`.
- `ui/history/HistoryScreen.kt` — ICAO, callsign, operator, registration, route,
  last-seen time, altitude, distance, message count, and tracked duration.
- Live / History tabs in `TextListScreen`, counts in the tab labels, selection
  survives rotation via `rememberSaveable`.

Re-appearing aircraft update their row instead of duplicating. 230 tests, 0
failures, APK builds.

### Session 8 (Migration Phase 0 goldens + offline enrichment): DONE, tested

Plan: `docs/ANDROID_MIGRATION_PLAN.md`.

**Phase 0 — behaviour capture.** `tools/phase0_goldens.py` drives the Python
receiver (read-only, never modified) over the three available fixtures and writes
per-frame and per-checkpoint TSV to `core-test/src/test/resources/fixtures/golden/`.
Components are driven directly rather than through `ADSBReceiver`, which threads and
reads wall-clock time. Output is byte-identical across runs, verified.

Three wall-clock leaks had to be closed to get determinism, each a real finding:
- `icao_cache.py` calls `time.time()` directly, so its 60 s TTL never expires during
  a fast replay — redirected at the virtual clock for the run.
- `AircraftState.first_seen` uses `default_factory=time.time`, bound at import and
  not redirectable; the first message timestamp per ICAO is tracked instead.
- **`MessageDecoder.decode()` never passes the frame timestamp into
  `DecodedMessage`**, so `timestamp` falls back to wall-clock at *decode* time.
  `AircraftManager._merge` uses that for `last_seen`, CPR pair ageing and
  `last_position_ts` — meaning the 10 s CPR pair window is measured against the
  wrong clock on any replay. Invisible live (microseconds apart), fatal on replay.
  Corrected in the harness; **the Python source is untouched and still has it**.

Captured: `modes1_iq` 491 frames / 1 aircraft (valid 180, parity_addr 233,
recovered 38, bad 38, corrected 2), `avr_20260621` 26,512 frames / 352 aircraft /
266 checkpoints, `avr_20260622` 13 frames / 3 aircraft. The AVR recordings were
written post-validation so they are 100 % CRC-valid — only the IQ fixture exercises
the CRC classifier, and it holds a single aircraft. **A live IQ capture is still
needed** and requires the dongle.

**Enrichment — the offline half, which is the half that works.**
- `enrich/Registration.kt` — port of `enrich/registration.py`, ICAO↔N-number by
  exact trie walk. Offline, instant, no key, no network.
- `enrich/Airlines.kt` — 155 operators by callsign prefix, **generated** from the
  Python table by `tools/gen_airlines.py` rather than transcribed.
- `enrich/Enrichment.kt` — `DataSource` provenance (ALGORITHMIC < DATABASE <
  NETWORK < DECODED) and `OfflineEnrichment`, applied on every update in
  `AircraftManager.applyEnrichment()` weakest-source-first.
- `AircraftState.flightAwareRoute` → `route` + `routeSource` (the value has come
  from adsbdb, not FlightAware, since Session 5 — the name was lying).
- **The route was being fetched, cached and never displayed.** Now rendered in
  `AircraftRow` with a provenance mark (`*` derived, `•` database, `~` online;
  decoded values unmarked), each with a content description.
- Route lookups are skipped for registration callsigns — a tail number is not a
  flight number, so adsbdb could only ever miss.

**Duplication collapsed.** `:app` now depends on `:core-test`, and the 19
hand-mirrored files (demod, crc, decoder, aircraft, location, capture, watchdog)
were deleted from `:app` — one definition each. `:app` is down to 30 Kotlin files,
all of them genuinely Android.

The stale `UsbRtlSdrSourceStub` that shadowed the real source is gone; its
constants moved to `capture/RtlSdrDefaults.kt` as the single definition, which the
Android `UsbRtlSdrSource` re-exports.

**Two green tests were asserting the Session 7 bug value.** `Phase3Tests` checked
`SAMPLE_RATE_HZ == 2_400_000` and `"-s 2400000"` in the launch URI, and passed —
because they tested the stub, which had never been corrected alongside the real
source. That is the duplication hazard realised: the fix landed in one copy and a
test kept certifying the other. Both now assert against
`Demodulator.REQUIRED_SAMPLE_RATE_HZ` so the constant and its consumer cannot
diverge silently again. A third test (`isOpen false before open`) only ever
asserted the stub's own literal and was deleted rather than kept green.

229 → 228 tests (215 core-test + 13 app), 0 failures, APK assembles.

**Measured on the 352-aircraft capture:** registration 20 → **326 (93 %)**,
operator **198 (56 %)**, at least one **96 %**. Previously the bundled 20-entry
`icao_db.json` resolved almost nothing.

**Parity:** Kotlin and Python agree on all 915,399 US addresses, verified by
SHA-256 over the full block (fixture is the digest plus 928 sampled rows — 13 KB,
not the 12.7 MB the full dump would cost). 229 tests pass (216 core-test + 13 app),
debug APK assembles.

### Session 7 (Decoder/CRC audit vs Python reference, gain control, Settings rebuild): DONE, tested

**Root cause of "Valid: 0".** The dongle was configured at 2.4 Msps while the demodulator
was written for 2.0 Msps (16-sample preamble, 2 samples/bit). Over a 112-bit frame that
drifts ~45 samples, so nothing decoded. The Python reference uses 2.0 Msps
(`config.py: sample_rate = 2_000_000`); `UsbRtlSdrSource.SAMPLE_RATE_HZ` now matches, and
`Demodulator.REQUIRED_SAMPLE_RATE_HZ` documents the coupling.

**Demodulator** rewritten as a faithful port of `demod/demodulator.py` (itself a dump1090
port). Corrected: preamble indices were doubled (`hp * 2` checked samples 0,4,14,18 instead
of 0,2,7,9); no noise floor at all, so every threshold crossing emitted a frame (~9200/sec
of garbage on hardware); short frames never emitted (`if (longBytes != null)` was always
true, so DF0/4/5/11 were sent as 14-byte frames and always failed CRC); magnitude LUT was
the FA 256x256 variant rather than dump1090's 129x129 x360, which the delta floor is
calibrated against; advance step and signal-level formula also realigned.

**CRC / parity.** Parity-address frames (DF0/4/5/16/20/21) were previously always INVALID,
so those DFs could never decode at all. Added `IcaoCache` (port of `crc/icao_cache.py`) and
the AP recovery path: the full-frame remainder *is* the candidate address, validated against
addresses confirmed by an earlier DF11/17/18. New `PARITY_ADDRESS` / `RECOVERED` statuses
mirror Python. Single-bit correction now covers only the 88 data bits, not the 24 CRC bits.

**Two deviations found and fixed in our favour, both verified:**
- Truncated DF17/18: a CRC-valid 7-byte frame claiming DF18 was credited with an ICAO,
  inventing a phantom aircraft. Python guards on length; we now do too.
- DF11 interrogator ID: **the Python reference has a bug here.** Its `remainder < 80` check
  uses `crc24(whole_frame)`, which is 0 for a valid frame but is not the II code when
  non-zero (II=7 surfaces as 0xFFD024), so the branch effectively never fires and genuine
  DF11 replies carrying an interrogator ID are discarded. Our `computeCrc` returns the
  spec-defined `CRC(data) XOR PI` syndrome (verified: 0, 7, 2, 20 on real frames). Kept
  deliberately; encoded as the one allowed divergence in `PythonParityTests`.

**Gain.** `AppConfig.autoGain` + device-reported levels. The rtl_tcp greeting carries tuner
type and gain count; `RtlTcpGain` maps that to librtlsdr's own per-tuner tables (verified
against `librtlsdr.c rtlsdr_get_tuner_gains`) and refuses to offer a list when the reported
count disagrees. Gain is applied over the live rtl_tcp control channel (cmd 0x03/0x04) with
no reconnect. `gainTenths` defaults to `GAIN_UNSET`, not 0 — 0 is the R82xx minimum-gain
step, so defaulting to it would silently pin the tuner to its least sensitive setting.

**Settings** rebuilt as a dedicated full-screen surface with explicit close, opaque panels,
>=7:1 text contrast and selected states carried by fill + accent border (not colour alone).
`ConfigChange.requiresPipelineRestart` limits reconnects to fields that define what we
connect to; observer/logging/watchdog/gain changes apply in place.

**Verification on hardware (Pixel 6 + R828D via OTG):** before = Total 1,043,100 / Valid 0 /
AC 0. After = Total 810 / Valid 284 / AC 6 with correct callsigns, altitudes, positions,
squawks and range. Settings open/close plus two live gain changes left uptime running
continuously at 00:04:35 (no reconnect). 209 unit tests pass, including a 467-frame
differential test against the Python reference covering DF 0/4/5/11/16/17/18/20/24.

### Session 6 (Live GPS observer position + generalized source watchdog): DONE, tested

**Context.** Primary use case: recording aircraft while driving with the phone tethered to
the antenna. Observer position must track the vehicle, but GPS is a real battery cost, so
every design choice here optimizes for "as little radio-on time as correctness allows."

**Real bug found and fixed via on-device testing.** Declaring
`foregroundServiceType="dataSync|location"` in the manifest made Android 14+ enforce the
location permission on *every* `startForeground()` call — including plain app startup in
Fixed mode, before GPS is ever touched. The original 2-arg `startForeground()` overload
implicitly requests the full manifest-declared type set, so the service crashed on launch
regardless of `observerMode`. Fixed by switching to `ServiceCompat.startForeground(..., type)`
with an explicit type subset: `FOREGROUND_SERVICE_TYPE_DATA_SYNC` only at startup/whenever
GPS isn't active, promoted to `DATA_SYNC or LOCATION` only when `observerMode == FOLLOW_GPS`
*and* the permission is actually granted (`PipelineService.updateForegroundLocationType()`).
Verified fixed by installing on the Pixel 6 and confirming clean launch with no crash.

**1. Observer position — Fixed vs Follow GPS**

- `AppConfig.observerMode: ObserverMode = FIXED` (`FIXED | FOLLOW_GPS`), persisted like
  other enum fields.
- **Fixed**: `observerLatitude`/`observerLongitude` (existing fields) used as-is, unchanged
  behavior. No location permission requested, no GPS calls made at all.
- **Follow GPS**:
  - Uses `FusedLocationProviderClient` (new `com.google.android.gms:play-services-location`
    dependency), `PRIORITY_BALANCED_POWER_ACCURACY` for continuous updates.
  - Continuous updates run **only** while `sourceState is Running` *and* `observerMode ==
    FOLLOW_GPS` — both conditions, via `GpsPolicy.shouldRunContinuousGps()`. Stopped
    immediately when either flips.
  - **Motion-aware throttle** (`GpsThrottlePolicy`, pure/testable): tracks consecutive
    fixes with <30m movement from the last accepted fix. Tiers (interval / min-distance):
    tier 0 (moving) 10s/50m -> tier 1 (>=1 stationary fix) 60s/75m -> tier 2 (>=3) 300s/100m
    -> tier 3 (>=6, floor) 900s/150m. Any fix >=30m from the last accepted one resets to
    tier 0 immediately. Delivery is batched (`setMaxUpdateDelayMillis`) so the OS can
    coalesce wakeups.
  - **Startup fresh fix**: on every app start, if `observerMode == FOLLOW_GPS`, request one
    `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` fix (never `getLastLocation()` / cached).
    Until it arrives (or if it fails), position stays on the persisted fixed coordinates —
    `ObserverPositionResolver` enforces this fallback explicitly, it never reports a fix
    that hasn't actually been received this run.
  - **Reconnect re-fix**: when `sourceState` transitions from not-Running back to Running
    (dongle replugged, network reachable again, etc.), the live fix is cleared and one fresh
    high-accuracy fix is requested again — a fix from before a multi-minute outage is not
    trusted, position falls back to fixed coordinates until the new one lands.
  - **Periodic re-fix**: every `gpsRefreshIntervalMinutes` (new `AppConfig` field, Settings-
    exposed, values **0 (off) / 15 / 30 / 60 / 120 / 360** minutes, default **60**), while
    Running and Follow GPS, force one more high-accuracy fix to correct drift on top of the
    continuous balanced-power stream. 0 disables this specifically (continuous updates are
    unaffected).
  - Each accepted fix is pushed straight into `decoder.observerLat/Lon` and
    `aircraftMgr.observerLat/Lon` — no pipeline restart.

**2. Permissions**

- `ACCESS_FINE_LOCATION` (manifest + runtime request, requested from `MainActivity` only
  when the user switches Settings to Follow GPS and it isn't already granted).
- `FOREGROUND_SERVICE_LOCATION` (manifest only, Android 14+ requirement for a foreground
  service with `foregroundServiceType` including `location`) — added alongside the
  existing `dataSync` type (`dataSync|location`), not replacing it.
- **No** `ACCESS_BACKGROUND_LOCATION` — the service is already a persistent foreground
  service with a visible notification, which is sufficient for foreground location access.
- Permission denied: Follow GPS silently never acquires a fix; position stays on fixed
  coordinates. No crash, no retry loop. Settings shows a warning line when Follow GPS is
  selected but the permission isn't granted.

**3. Antenna/source-absence watchdog (generalized)**

- Replaces the USB-only 5-minute watchdog added in Session 5's follow-up. Now applies to
  **any** configured source type — Network host unreachable, USB dongle absent, file replay
  missing, etc. (user-confirmed scope decision).
- `AppConfig.sourceWatchdogTimeoutMinutes: Int = 5`, Settings-exposed, values **0 (off) / 1
  / 5 / 10 / 15 / 30** minutes.
- Extracted into a small reusable `SourceWatchdog` class: starts a single timer the moment
  `sourceState` leaves `Running`; cancelled immediately the moment it returns to `Running`.
  A timer is not restarted by intermediate state churn during an outage (e.g. repeated
  Error/Connecting cycling from the existing auto-reconnect loop) — only a return to
  `Running` cancels it, so transient reconnects that recover before the timeout never
  trigger a shutdown, and outages don't accumulate time across separate disconnect/
  reconnect cycles.
  - `timeoutMinutes <= 0` disables the watchdog entirely.
  - On expiry: `ErrorLog.warn(...)` then `stopSelf()` — same "stop the foreground service"
    behavior as before, battery-draining background work stops even if `MainActivity` is
    still on screen.

### Session 5 (Comm-B decoder + CSV export + raw logger + route enrichment): DONE, tested
- Comm-B BDS register decoder: new CommB.kt (core-test + app) decodes BDS 4,0 (selected
  vertical intention), 5,0 (track and turn), 6,0 (heading and speed) from DF20/21 MB fields.
  MB field never self-identifies its register, so candidates are validated by reserved-bit/
  range checks and disambiguated by picking whichever candidate explains more active fields
  (real registers populate most of their fields; accidental collisions only satisfy the bare
  minimum). Wired into MessageDecoder.decodeDF20/21 (skipped when the BDS 2,0 callsign check
  already matched); merged non-destructively into AircraftState via AircraftManager.applyCommB()
- CSV export: AircraftHistoryDao.getAll() query + new CsvExporter.kt (app) writes aircraft_history
  to a timestamped CSV under external app storage. Callable, not wired to any UI button yet.
- Raw message logger: new RawMessageLogger.kt (app), daily-rotating dump1090.log-style file;
  gated by new AppConfig.rawLoggingEnabled (persisted via AppConfigStore), wired into
  PipelineService.processFrames
- Route enrichment: new RouteEnrichment.kt (app) using the existing Ktor client dependency
  against adsbdb.com's free public API (no key, no scraping ToS risk) instead of FlightAware —
  closes the "multi-source route data" gap with one legitimate source. Cached in the existing
  EnrichmentCacheDao (24h TTL); gated by the existing AppConfig.enrichmentEnabled flag; wired
  into PipelineService via AircraftManager.setRoute(), at most one lookup in flight per ICAO
- 6 new CommBTests; full suite now 134 JVM tests, all pass
- Verified against a real live antenna feed (dump1090 --net on the dev PC, RTL-SDR dongle
  attached): captured 3495 real AVR lines over 3 minutes via a throwaway JVM harness (not
  committed — machine-specific capture path) run through MessageDecoder+AircraftManager;
  1373 CRC-valid messages, 31 real aircraft tracked with correct callsign/altitude/position/
  speed, zero exceptions. DF17/18/11 paths fully exercised this way. DF20 traffic was present
  in the raw capture (2 frames) but both failed CRC before reaching the decoder (short/rare
  replies don't get single-bit correction, unlike DF17/18) — so the new Comm-B bit-decode logic
  itself is verified only by CommBTests' bit-exact synthetic vectors, not yet against a real
  CRC-valid over-the-air Comm-B message. Also installed the rebuilt debug APK on the Pixel 6
  over USB and confirmed clean launch (no crash, no AndroidRuntime errors)
- Correction to the note below: history insert() has been wired since Session 3
  (PipelineService.maybeInsertHistory) — it was not actually dead code

### Session 1 (Tier 1 correctness): DONE, tested
- Speed clamp: >700kt rejected in mergeAdsb + mergeAltitude
- Altitude clamp: <-1500ft or >72000ft rejected in mergeAdsb + mergeAltitude
- CPR relative decode: decodeCprRelative() implemented (DO-260B A.1.7.3.3) with cprModD helper; observerLat/observerLon mutable on MessageDecoder; PipelineService wires AppConfig coords at pipeline start
- Gillham TODO comments removed (modeAToModeC was already correct -- stale TODOs only)

### Session 4 (Sort + purge + ICAO lookup): DONE, tested
- Sort: aircraft list now sorts nearest-first (distanceNm ascending, nulls last), then most-recently-seen
- Purge: PipelineService launches a coroutine on onCreate that runs hourly, trimming history > 7 days via AircraftHistoryDao.purgeOlderThan()
- ICAO lookup: IcaoEntry data class (core-test + app); IcaoLookup.kt loads assets/icao_db.json (org.json, no new dep); AircraftManager.setLookup() stores the map and applies reg/op/type on every update (non-overwriting); sample DB bundled with 20 real entries; full DB: replace icao_db.json with any flat ICAO-hex-keyed JSON using {reg, op, type} fields
- 3 new tests (lookup enriches, doesn't overwrite, sort order); suite now 122 tests, all pass

### Session 3 (Signal + distance/bearing + Room history): DONE, tested
- Distance/bearing: haversineNm + bearingDeg added to AircraftManager; computed in mergeAdsb when observer and lat/lon are available; fills distanceNm + bearingDeg on AircraftState
- Observer wired into AircraftManager in PipelineService (alongside decoder observer wiring already there)
- Room insert: PipelineService injects AircraftHistoryDao; maybeInsertHistory() fires max once per 30s per ICAO when aircraft has a position; IO dispatcher; error swallowed with runCatching
- AircraftRow: third line added showing Dist / Brg / Sig (all graceful on null)

### Session 2 (Tier 2 decoder completeness): DONE, tested
- TC31 Aircraft Operational Status: decodeTC31() decodes version, NICa, NACp, GVA, SIL (subtype 0/1), TCAS flag; fields added to AdsbFields + AircraftState; merged in AircraftManager
- II code (DF11): decodeDF11() extracts bytes[6] & 0x0F -> iiCode on AllCallReply + AircraftState; mergeAllCall() added
- DF22/23/24: explicit Unknown dispatch branches added (previously fell through to else)
- All six files synced between core-test and app modules
- 21 new Phase2Tests added; total suite 116 tests, all pass

## Phase 3 - USB OTG: DONE (untested on real OTG hardware - driver app marto.rtl_tcp_andro confirmed installed on Pixel 6, iqsrc:// Intent flow built, SdrSourceActivity trampoline fixed from Theme.NoDisplay crash to Theme.Translucent). Live network bridge (PC dump1090 -> phone over WiFi) verified working as dev/test substitute for OTG.
## Phase 4 - Map layer: NOT STARTED (MapScreen.kt placeholder only)

## Known bug fixed this session
NetworkSource had socket.soTimeout=10000; any I/O hiccup threw inside runAvrLoop's bare while(true)
with no try/catch, silently killing the coroutine while TCP stayed ESTABLISHED (confirmed via netstat:
149880 bytes stuck unread in Recv-Q). Fixed: removed timeout, added try/catch with auto-restart in both
runAvrLoop and runIqLoop, added null-streak guard (50 consecutive nulls forces reconnect), added ErrorLog.

## Feature gap vs Python CLI (github.com/2139avi/adsb, v9.6) - NOT yet ported to Android
- Signal level column w/ unicode bar (Python TUI has it) — UI work, out of scope for backend sessions
- ICAO registration/operator lookup: sample DB only (20 entries); full FAA/OpenSky dataset not yet swapped in

## Immediate next steps
1. Replace icao_db.json with a full dataset (FAA registry / OpenSky / dump1090-fa aircraft.json)
2. Phase 4 map layer — MapScreen.kt is a placeholder; needs OSMDroid or MapCompose
3. Wire CsvExporter / rawLoggingEnabled toggle / route enrichment toggle into a settings UI (all three exist and work, just no UI entry point yet)
4. Get a real CRC-valid DF20/21 message through the Comm-B decoder on live traffic to confirm the BDS40/50/60 bit-decode against actual hardware (only synthetic vectors tested so far — see Session 5)
