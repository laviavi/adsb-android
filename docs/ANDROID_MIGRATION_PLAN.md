# Android Migration Plan — ADS-B Receiver

Status: **planning only**. No Android production code, dependencies, or Python
changes are made by this document.

Date: 2026-07-25

> **Scope decision — single source.** The app has exactly one signal source: the
> USB RTL-SDR dongle on OTG. Network, file, dummy and text-replay sources are
> **removed from the product**. File/AVR replay survives *only* inside the JVM test
> source set, where it is the parity harness and never ships. Consequences are
> marked **[1-src]** throughout.

---

## 1. Selected Python source of truth

### Selection

| | |
|---|---|
| **Path** | `D:\SDR\adsb_v9_5` |
| **Package version** | `9.0.6` (`pyproject.toml`), display label from `adsb_receiver/version.py` |
| **Changelog head** | v8 (2026-06-15); directory dated 2026-06-21 |
| **VCS** | none — no `.git` in any `D:\SDR` project. Version identity is directory + `pyproject.toml` + `CHANGELOG.txt` only |
| **Scale** | 51 modules / ~12.5 kLOC package code, 7 test modules / **169 tests**, 1 IQ fixture (`tests/fixtures/modes1.bin`), 2 AVR recordings, 2 runtime logs |

### Evidence considered

| Candidate | Date | Py files | LOC | Verdict |
|---|---|---|---|---|
| `adsb_v9_5` | Jun 21 23:41 | 51 (+51 nested copy under `adsb console/uploads/`) | 12,532 | **Selected** |
| `ADSBweb_v1` | Jun 22 15:11 | 55 | 12,525 | Rejected — see below |
| `adsb_v9_4` | Jun 21 17:00 | 55 | 12,769 | Superseded by v9_5 (same file set, older) |
| `adsb_v7j` | Jun 14 | 0 | — | Empty except `meta_cache.json` |
| `obsolete/*` (v5–v7k, ~20 dirs) | May–Jun | — | — | User-marked obsolete |
| `sdranalyze`, `iqsweep` | Jun 20–25 | — | — | RF benchmark harnesses, not receivers |
| GitHub | — | — | — | **Not consulted.** `D:\SDR` is complete and self-consistent; per instruction, GitHub is a fallback only. A `search_repositories` probe for the account referenced in `docs/PHASE_PROGRESS.md` returned 422 (no accessible repos), so no online candidate could be shown to be newer anyway |

### Why `adsb_v9_5` over `ADSBweb_v1`

`ADSBweb_v1` is 1 day newer and derived from v9_5, but it is a **UI fork, not a
capability advance**:

- `diff` of the `adsb_receiver/` module trees is **empty** — identical file sets.
- Its own README states the backend "runs unchanged"; only the CLI is replaced.
- `web/server.py` is *smaller* than v9_5's (19,331 vs 19,748 bytes) — v9_5 already
  contains the web layer; the fork adds `static/{index.html,app.js,style.css}` and
  drops CLI entry points.
- v9_5 retains the **curses + summary console UI**, which is the functional
  reference this migration must preserve. The web fork deletes it.

`adsb_v9_5` is therefore the superset of implemented capability and the correct
behavioural reference. The `ADSBweb_v1` static assets remain a useful *secondary*
reference for field grouping only — **not** as an Android design target.

### Caveats to record

- `adsb_v9_5/pyproject.toml` has corrupted tool config: `[tool.mypy] python_version
  = "9.0.6"` and `[tool.ruff] target-version = "9.0.6"` (version string pasted into
  version-of-Python fields). Cosmetic; does not affect runtime. **Not fixed** —
  Python is read-only in this task.
- `adsb_v9_5/adsb console/uploads/adsb_v9_5/` is a full nested duplicate of the
  project. Ignore it; it doubles file counts in naive scans.
- Known decoder bug carried into the Android port deliberately **as a divergence,
  not a copy**: `crc/checker.py` DF11 interrogator-ID branch (`remainder < 80`) uses
  `crc24(whole_frame)` instead of the spec syndrome `CRC(data) XOR PI`. Android is
  already correct here; see `core/receiver/.../PythonParityTests.kt`.

---

## 2. Receiver-core audit

### 2.1 Pipeline shape (as implemented)

```
capture/source.py:create_source()
   ├─ rtlsdr | file      → raw IQ bytes ─→ demod/demodulator.py:process_buffer()
   └─ network | dummy    → pre-framed   ─→ core.py:_make_raw_from_bytes()
      | textfile
                              ↓ RawMessage
                     crc/checker.py:CRCChecker.check()  ←→ crc/icao_cache.py
                              ↓
                     decoder/adsb_decoder.py:MessageDecoder.decode()
                              ↓ DecodedMessage
                     aircraft/manager.py:AircraftManager.update()  (+ CPR pairing)
                              ↓ AircraftState
      ┌───────────────────────┼──────────────────────────┐
 ui/summary.py        output/emitter.py        observability/manager.py
 ui/terminal.py       (JSON/log/record)        (coverage, performance, fa_log CSV)
 web/server.py                                 aircraft/historic.py (historic CSV)
```

### 2.2 Layer-by-layer findings

**SDR / USB input and device lifecycle** — `capture/source.py`
- 5 sources: `RTLSDRSource`, `FileSource`, `NetworkSource`, `DummySource`,
  `TextFileSource`. Uniform `start/stop/read() -> bytes|None` contract, `None`
  = exhausted, `b""` = retry.
- Device-loss recovery is **in the source, not the pipeline**: `RTLSDRSource.read()`
  catches any read exception, closes the handle, then loops `_open_device()` every
  3.0 s **forever** (`_MAX_RECONNECT_ATTEMPTS = 0`) until `stop()`. Returns `b""` on
  success so the pipeline thread never dies. → Android's `SourceWatchdog` +
  auto-reconnect already mirrors this; the *infinite* retry is what makes the
  antenna-loss shutdown timer necessary.
- Two DLL-era workarounds that are Windows-only and **must not be ported**:
  `rtlsdr_set_dithering` ctypes stub, and the "skip ppm=0 write" quirk. The latter
  *is* behaviourally relevant — RTL-SDR Blog V4 dongles reject a 0-ppm write.
- `NetworkSource` is line-framed AVR (`*HEX;`), 2.0 s socket timeout, returns at
  most **one frame per `read()`** — a known throughput cap in Python.

**IQ capture / buffering / sample rate / demod** — `demod/demodulator.py`, `config.py`
- `sample_rate = 2_000_000` is the contract. `read_size = 262_144` bytes = 131,072
  IQ pairs ≈ 65.5 ms per buffer.
- dump1090-derived: 129×129 magnitude LUT (×360), 10-condition preamble inequality
  chain on samples 0–9, PPM slicing with `delta < 256` carry-previous-bit, noise
  floor `delta_floor` (default 2550).
- **Two live-tuneable knobs** exposed to the operator at runtime via the console:
  `preamble_gap_divisor` (3–12, `[` `]`) and `delta_floor` (255–5100 step 255,
  `{` `}`). These are the receiver's only real-time RF tuning controls and are
  currently **absent from Android**.
- `DemodConfig.preamble_threshold`, `noise_floor_samples`, `enable_mlat` are
  declared but unused/reserved.

**CRC / parity / correction** — `crc/checker.py`, `crc/icao_cache.py`
- Five statuses: `valid`, `corrected`, `recovered`, `parity_addr`, `bad`.
- `parity_addr` = DF 0/4/5/16/20/21 whose address-XOR'd CRC could not be resolved
  against the ICAO cache → **dropped** in `core.py:_process_raw` (ICAO would be
  garbage). `recovered` = same family, resolved via cache → counted as valid.
- ICAO cache: 1024 slots, 60 s TTL, collisions overwrite (dump1090 semantics).
- Single-bit correction is DF17/18-only and covers the 88 data bits, not the CRC.
- Counter policy in `stats.py`: `valid` is incremented for `valid`, `corrected`
  **and** `recovered`; `corrected`/`recovered` are also counted separately. The UI's
  "noise" figure is `bad_crc` alone, and `parity_addr` is shown as "unresolved" —
  a three-way split Android currently collapses.

**Decoder coverage** — `decoder/adsb_decoder.py` (747 lines)

| DF | Handled | Notes |
|---|---|---|
| 0 | ✅ | air-air short + `decode_tcas_df0` |
| 4 | ✅ | altitude reply (Gillham/13-bit) |
| 5 | ✅ | identity reply (squawk) |
| 11 | ✅ | all-call, II code extraction |
| 16 | ✅ | air-air long + `decode_tcas_df16`, RA MV decode, intruder ICAO |
| 17/18/19 | ✅ | extended squitter |
| 20/21 | ✅ | Comm-B (altitude/identity + BDS on Android side) |
| 22/23/24 | ⚠️ | reach `_dispatch` else-branch → Unknown |

TC coverage inside `_decode_me`: 1–4 identification, 5–8 surface position, 9–18
airborne baro, 19 velocity, 20–22 airborne GNSS, 28 aircraft status, 29 target
state, 31 operational status. **TC 23–27, 30 unhandled** (matches Android).

- `_resolve_ap_icao(data, known_icaos)` — parity-address recovery against the live
  aircraft set, kept in sync by `AircraftManager` calling
  `decoder.update_known_icaos()`. This is a *second* recovery path alongside the
  ICAO cache; Android implements only the cache path.
- CPR: `decode_cpr_position()` global even/odd, NL table, surface + airborne.
  Pairing lives in the **manager**, not the decoder.

**Aircraft state manager** — `aircraft/manager.py`, `aircraft/models.py`
- Merge rule is uniformly **non-destructive last-write-wins**: every field is
  `if msg.X is not None: state.X = msg.X`. No staleness invalidation — a value
  written once persists until the aircraft expires. **This is the single most
  important behaviour to reproduce, and the biggest UI risk**: the CLI shows an
  altitude from 55 s ago identically to one from 0.5 s ago. Android must reproduce
  the *state* but **must not** reproduce the ambiguity in the *display* (§4.6).
- CPR pair validity: both frames present **and** both younger than
  `cpr_max_age_seconds` (10.0). Result range-checked to ±90/±180, rounded to 6 dp,
  stamps `last_position_ts`.
- Expiry: `purge_expired()` on a background thread every `min(expiry/4, 15)` s;
  default `expiry_seconds = 60`.
- Per-aircraft: `message_history` deque (default 50), `signal_levels` deque (20) →
  `avg_signal` property, `interrogator_ids` set → `"3;7"` string.
- TCAS state machine: `tcas_event_count` increments only on a **rising edge** into
  `tcas_ra_active`; `tcas_ra_terminated` keeps last known advisory text.
- Android delta already present: speed >700 kt and altitude outside
  −1500…72000 ft are rejected. Python has **no such clamp**. Keep Android's; flag
  as an intentional divergence.

**CLI / console display** — `ui/summary.py` (1058 lines) — full inventory in §4.9.
- Two distinct UIs exist: `ui/summary.py` (`--summary`, ANSI, Windows-safe, the
  advanced one) and `ui/terminal.py` (curses, requires `windows-curses`). **The
  summary display is the reference.**
- Flicker-free strategy: first frame `cls`, thereafter `ESC[H` + per-line `ESC[K`,
  `ESC[J` at end. 1 Hz default.
- Stable row order: `_active_order` list preserves first-seen order and only
  appends new ICAOs — rows never jump. **Critical UX property to preserve.**
- Age colouring: ≤5 s green, ≤15 s yellow, else dim.
- Historic section: aircraft silent 60 s move to a separate table and export to
  `historic_YYYY-MM-DD.csv`; re-appearance moves them back and resets enrichment.

**Web / API** — `web/server.py`: FastAPI + WebSocket broadcast, endpoints
`/api/status`, `/api/aircraft`, `/api/aircraft/{icao}`, `/api/stats`,
`/api/config` (GET/POST), `/api/control/{start,stop,restart}`, plus a 500-entry
in-memory log ring. Useful as a **field-set reference**; not an Android target.

**Observability** — `observability/`
- `performance.py`: 1 row / 60 s, 20 columns (msg totals + deltas, rate, decode
  success ratio, CRC failure ratio, CRC recovery ratio, active aircraft, new ICAOs
  this interval, FA query counters, `diagnosis_hint`).
- `coverage.py`: 1 row / 300 s — 8 compass sectors × {count, max, median, p90
  miles}, 4 altitude bands, **symmetry score 0–100**, best/worst sector. This is a
  genuine antenna-diagnostic feature with no Android equivalent.
- `csv_writer.py`: daily rotation, both UTC and local timestamps + tz name + offset
  in every row.
- `debug_bundle.py`: IQ ring buffer + message log + config + stats + version → one
  timestamped zip. Direct model for the Android "export diagnostic bundle".

**Tests / fixtures** — 169 tests across 7 modules: `test_crc` 13, `test_decoder` 21,
`test_demod` 17, `test_aircraft` 24, `test_golden` 27, `test_property` 24 (hypothesis),
`test_web` 43. Fixtures: `tests/fixtures/modes1.bin` (raw IQ), `recordings/adsb_2026062{1,2}.txt`
(AVR text). Deps: numpy (core), pyrtlsdr/fastapi/uvicorn (extras).

---

## 3. Capability / migration matrix

Priority: **P0** blocking, **P1** required for parity, **P2** valuable, **P3** deferred.
Method: **Port** (rewrite in Kotlin) · **Lib** (replace with Android library) ·
**Native** (Android platform capability) · **Drop** · **Defer**.

### 3.1 Capture

| Python capability | Source | Current UI exposure | Android target | Method | Pri | Risk / dependency | Validation |
|---|---|---|---|---|---|---|---|
| RTL-SDR direct device | `capture/source.py:RTLSDRSource` | CLI flags | `UsbRtlSdrSource` — **the only source** | Port ✅ done | P0 | OTG driver app dependency | live hardware |
| Infinite 3 s reconnect loop | same, `read()` | log lines | exists + `SourceWatchdog` | Port ✅ done | P0 | battery | unplug/replug test |
| File IQ replay | `FileSource` | `--source file` | **test source set only** | Port ✅ done, move | P1 | must not ship | replay `modes1.bin` |
| Text (AVR) file replay | `TextFileSource` | `--source textfile` | **test source set only** | Port | P1 | must not ship | Phase 2 |
| AVR text network | `NetworkSource` | `--source network` | **delete** **[1-src]** | Drop | — | was the dev bridge | — |
| Raw-IQ network (rtl_tcp) | *not in Python* | — | **delete** **[1-src]** | Drop | — | — | — |
| Dummy source | `DummySource` | `--source dummy` | **delete** **[1-src]** | Drop | — | — | — |
| Gain / auto-gain | `cfg.gain`, `-1`=auto | `--gain` | `RtlTcpGain` (exists) | Port ✅ done | P0 | per-tuner tables | hardware |
| PPM correction | `ppm_correction` | `--ppm` | exists | Port ✅ done | P1 | V4 zero-ppm quirk | hardware |
| Bias-tee | `bias_tee` | `--bias-tee` | `RtlTcpGain.CMD_SET_BIAS_TEE` (exists) | Port ✅ done | P2 | rtl_tcp cmd 0x0E; unverified on hardware | needs bias-tee LNA |
| Device index | `device_index` | `--device` | n/a (single OTG device) | Drop | — | — | — |

**[1-src] What deleting the other sources removes**, beyond the source files
themselves: `AppConfig.sourceType/networkHost/networkPort/networkFormat/filePath`,
the entire Settings → Source group, `SourceType`/`NetworkFormat` enums, the source
glyph in the app bar, and — most usefully — **`ConfigChange.requiresPipelineRestart`
collapses to a single field, `ppmCorrection`**. Every other setting becomes live.
The cost is that the PC-side dump1090 bridge, which was the development substitute
for OTG hardware, is gone: from Phase 3 on, *all* live testing needs the dongle
physically attached to the phone. Replay fixtures cover everything below Phase 3.

### 3.2 Demod / CRC / decode

| Capability | Source | UI exposure | Android target | Method | Pri | Risk | Validation |
|---|---|---|---|---|---|---|---|
| 2.0 Msps demod, 129×129 LUT | `demod/demodulator.py` | — | `Demodulator` (exists) | Port ✅ done | P0 | sample-rate coupling | 467-frame parity test |
| Live `preamble_gap_divisor` | same | `[` `]` keys | **missing** | Port + UI | **P1** | changes reception live | A/B accept-rate |
| Live `delta_floor` | same | `{` `}` keys | **missing** | Port + UI | **P1** | can zero out reception | A/B accept-rate |
| Rolling accept/reject window | `ui/summary.py` `<` `>` | stats row 3 | **missing** | Port + UI | P1 | — | Phase 4 |
| CRC 5-state + ICAO cache | `crc/*` | stats row 2 | `CrcChecker`, `IcaoCache` (exists) | Port ✅ done | P0 | — | parity test |
| AP recovery via known-ICAO set | `_resolve_ap_icao` | — | **missing** (cache path only) | Port | P2 | second recovery path | parity test on DF0/16 |
| DF 0/4/5/11/16/17/18/20/21 | `adsb_decoder.py` | detail panel | exists | Port ✅ done | P0 | — | parity test |
| DF 22/23/24 → Unknown | `_dispatch` else | — | exists | Port ✅ done | P3 | — | parity test |
| TC 1–22, 28, 29, 31 | `_decode_me` | detail panel | exists | Port ✅ done | P0 | — | parity test |
| TC 23–27, 30 | *unhandled* | — | unhandled | Defer | P3 | matches reference | — |
| CPR global + surface | `decode_cpr_position` | Lat/Lon cols | exists | Port ✅ done | P0 | — | parity test |
| CPR relative (observer) | *not in Python* | — | exists | Android-only | P2 | needs observer pos | unit |
| TCAS DF0/DF16, RA, intruder | `decode_tcas_df*` | detail panel, `!` prefix | exists | Port ✅ done | P1 | rare live traffic | synthetic + capture |
| Comm-B BDS 4,0/5,0/6,0 | *Android-only* | — | `CommB.kt` (exists) | Android-only | P2 | unverified on air | long capture |
| Speed/alt sanity clamps | *not in Python* | — | exists | Android-only ⚠️ | P1 | intentional divergence | documented in parity test |

### 3.3 State / enrichment

| Capability | Source | UI exposure | Android target | Method | Pri | Risk | Validation |
|---|---|---|---|---|---|---|---|
| Non-destructive merge, expiry | `aircraft/manager.py` | table | `AircraftManager` (exists) | Port ✅ done | P0 | — | replay parity |
| Per-aircraft msg history (50) | `models.py` | detail panel | exists | Port ✅ done | P1 | memory at scale | soak |
| Signal deque(20) → `avg_signal` | `models.py` | Signal bar col | partial | Port | P1 | rtl_tcp has no per-frame RSSI on AVR | Phase 4 |
| II codes set → `"3;7"` | `models.py` | II Codes col | exists | Port ✅ done | P2 | — | unit |
| Historic tracker + daily CSV | `aircraft/historic.py` | Historic section | Room `aircraft_history` (partial) | Port | P1 | different storage model | Phase 5 |
| `field last_position_ts` | `models.py` | — | needed for staleness | Port | P1 | drives §4.6 | unit |
| FAA registry DB (pickle, auto-download) | `enrich/aircraft_meta.py` | Registration col | `icao_db.json` 20 rows | Lib/Defer | P2 | ~100 MB dataset, offline | Phase 5 |
| hexdb / OpenSky / adsbdb lookup | `enrich/aircraft_meta.py` | Reg/Type/Airline | `RouteEnrichment` (adsbdb only) | Port | P2 | network, privacy | Phase 5 |
| ICAO→N-number algorithmic | `enrich/registration.py` | Registration col | **missing** | Port | P2 | **offline, zero-cost, US only** — high value | unit vs Python |
| FlightAware HTML scrape | `enrich/flightaware.py` | Route `+` marker | **do not port** | Drop | — | ToS / fragile / battery | — |
| adsbdb route fallback (`~`) | `enrich/lookup.py` | Route `~` marker | `RouteEnrichment` (exists) | Port ✅ done | P2 | network | Phase 5 |
| Airline name from callsign | `enrich/lookup.py:airline_name` | Airline col | **missing** | Port | P2 | static table, offline | unit |
| OS-level location detect | `enrich/location.py` | `--lat/--lon` | Fused location (exists) | Native ✅ done | P1 | permission | Phase 5 |
| Nominatim address geocode | `enrich/location.py` | `--location` | Android Geocoder | Native | P3 | — | Phase 5 |

### 3.4 Output / observability

| Capability | Source | UI exposure | Android target | Method | Pri | Risk | Validation |
|---|---|---|---|---|---|---|---|
| JSON-lines / message print | `output/emitter.py` | stdout | Logs screen + export | Port | P2 | — | Phase 5 |
| `--record` AVR recording | `OutputConfig.record_file` | — | `RawMessageLogger` (exists) | Port ✅ done | P1 | storage | Phase 5 |
| Performance CSV (60 s, 20 col) | `observability/performance.py` | — | `PerformanceMetrics`/`PerformanceCsvLogger` (exist) | Port ✅ backend+CSV done | P1 | FA columns always 0 (FA dropped, see below) | formula-verified vs Python, see `docs/PLAN_STATUS.md` §11 |
| Coverage CSV (300 s, sectors, symmetry) | `observability/coverage.py` | — | `CoverageMetrics`/`CoverageCsvLogger` (exist); Receiver screen polar card not built | Port ✅ backend+CSV done, UI deferred | P1 | reuses `AircraftState.distanceNm/bearingDeg`, no separate observer-position skip (always set) | formula-verified vs Python, see §11 |
| FA scraper log | `observability/fa_log.py` | — | n/a (FA dropped) | Drop | — | — | — |
| Debug bundle zip | `debug_bundle.py` | `--debug-bundle` | Share-sheet bundle | Port | P1 | privacy (IQ + position) | Phase 5 |
| Dual UTC+local timestamps | `observability/csv_writer.py` | CSV | `CsvTimestamps` (exists) | Port ✅ done | P1 | tz display name is OS-supplied, not string-diffable | value spot-checked, not column-diffed |
| FastAPI/WebSocket server | `web/server.py` | browser | **do not port** | Drop | — | battery, attack surface | — |

---

## 4. Android architecture recommendation

### 4.1 Layer decisions

**Everything is ported to Kotlin. No Python is retained on-device. No hybrid.**

| Layer | Decision | Rationale |
|---|---|---|
| Source | **One source: `UsbRtlSdrSource`.** No abstraction, no factory, no `SourceType` **[1-src]** | With a single implementation, the `BaseSource` interface earns nothing — it is an interface with one production implementor, which the plan otherwise forbids. Keep a minimal `IqSource` interface **only** because the JVM replay harness is a second implementor; that is a real second implementor, not a speculative one. |
| USB / RTL-SDR | **Kotlin**, via existing `iqsrc://` driver-app Intent + rtl_tcp control socket | Performance: irrelevant (I/O-bound). USB/native: Android grants USB device access to the *foreground app that holds the permission*; a bundled CPython + libusb would need its own permission grant and an NDK build of librtlsdr — weeks of work for zero capability gain. Battery: one process, one wake path. Offline: no change. Maintainability: one language. Testability: JVM unit tests, already in place. |
| Demod (hot loop) | **Kotlin**, `IntArray`/`ByteArray`, no allocation in the inner loop | Performance: ~2 M samples/s × 2 bytes = 4 MB/s. Python needs numpy vectorisation to keep up; JIT'd Kotlin on ARM64 handles it scalar. This is the one layer where a native (NDK) rewrite might later be justified — **only if** profiling on Phase 6 shows >15 % of a big core. Do not pre-optimise. |
| CRC / ICAO cache | **Kotlin** | Pure arithmetic, trivially portable, already done and parity-tested. |
| Decoder | **Kotlin** | Pure function of bytes → data class. Highest parity-test value per line. |
| Aircraft state | **Kotlin**, single-writer coroutine, `StateFlow<List<AircraftState>>` | Python uses `threading.RLock` + polling; coroutines give the same single-writer guarantee without lock contention on the UI read path. |
| Repository / observable boundary | **New Kotlin layer** — see §4.2 | Python has no such layer; the UI polls the manager directly at 1 Hz. On Android this is exactly where high-update-rate control belongs. |
| Persistence | **Room** (history, sessions, diagnostics rows) + **DataStore** (settings) | Room replaces the daily-CSV files as the *store*; CSV becomes an *export format*. DataStore replaces `ReceiverConfig` JSON. |
| Enrichment cache | **Room** (exists) | Replaces the Python pickle/JSON disk caches. |
| Map | **osmdroid** (library) | Only mature offline-capable, no-API-key, LGPL Android map. Google Maps Compose requires Play Services + a key + online tiles — unacceptable for a field SDR tool. MapLibre is the alternative if vector tiles become a requirement; heavier. |
| Charts | **Hand-rolled Compose `Canvas`** | The needed charts are a sparkline, a stacked rate bar, and a polar coverage plot. A charting library is more surface area than the ~200 lines these need, and none of them render a polar coverage plot well. Ladder rung: stdlib/native wins. |
| CSV export | **`kotlin.text` + `Uri`/SAF** | No dependency justified. |
| Foreground service | **Existing `PipelineService`**, `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (+`LOCATION` only when Follow GPS is active and granted) | Already fixed in Session 6; keep. |
| DI | **Hilt + KSP** (existing) | House default, already wired. |
| Navigation | **Navigation Compose** + `NavigationSuiteScaffold` | Single nav graph adapts bar↔rail↔drawer by window size class. |

**Rejected: hybrid (Chaquopy / local Python service).** It would give a
short-term shortcut for the enrichment layer only — and that layer is the *least*
valuable and most network-dependent part. It costs: ~40 MB APK, no USB access from
the Python side (so the pipeline would still be Kotlin), a second GC, a second
crash surface, no Play Store review benefit, and it would make the parity test
meaningless (you can't test "Kotlin matches Python" when you shipped Python). No
concrete short-term migration advantage exists. Decline.

### 4.2 Target module graph

```
:app                     Compose UI, navigation, service, DI wiring
  └─ :core:ui            design tokens, shared components, formatters
  └─ :data               Room, DataStore, export, enrichment repos
       └─ :core:receiver capture · demod · crc · decoder · aircraft (pure Kotlin/JVM)
            └─ :core:model  AircraftState, DecodedMessage, RawFrame, enums — zero Android deps
```

Phase 1 already collapsed the `:app`/`:core-test` duplication (~20 verbatim
files) that used to make a decoder fix require two edits, and Step 3
(2026-07-26) renamed `:core-test` to `:core:receiver` — a plain JVM module
`:app` depends on and JVM tests run against directly. **`:core:model` has not
been extracted**: it has no consumer yet, since `:core:ui` and `:data` (the two
modules that would want model-only types without the receiver logic) are
Phase 4/5 work and don't exist. The graph above is the target once they do.

### 4.3 Threading and back-pressure

| Stage | Dispatcher | Back-pressure rule |
|---|---|---|
| USB read | `Dispatchers.IO`, dedicated single thread | blocking read, fixed 256 KiB buffer, reused |
| Demod | same thread as read (no hand-off; avoids copying 4 MB/s) | — |
| CRC + decode | same thread | — |
| State merge | `Dispatchers.Default`, single actor coroutine, `Channel(capacity = 1024, onBufferOverflow = DROP_OLDEST)` | dropping decoded frames under overload is correct: the aircraft table is a *latest-value* view. Dropped count is a **counter shown on the Receiver screen**, never silent. |
| Repository → UI | `StateFlow`, emitted at a fixed **4 Hz** tick, not per message | §4.7 |
| Map | separate `StateFlow` at **2 Hz**, positions only | §4.7 |

### 4.4 Observable repository contract

```
ReceiverRepository
  val receiverStatus:  StateFlow<ReceiverStatus>   // 1 Hz  — state, uptime, source, gain
  val liveMetrics:     StateFlow<LiveMetrics>      // 1 Hz  — rates, CRC split, counts
  val aircraft:        StateFlow<List<AircraftRow>>// 4 Hz  — pre-formatted, stable order
  val mapAircraft:     StateFlow<List<MapMarker>>  // 2 Hz  — position subset only
  val selected:        StateFlow<AircraftDetail?>  // 4 Hz  — only while a detail pane is open
  val events:          SharedFlow<DiagnosticEvent> // as they occur, replay 500
  fun setGain / setDemodTuning / start / stop / reconnect
```

`AircraftRow` is a **UI model, not the domain state** — pre-formatted strings plus
a per-field freshness enum, computed once on `Dispatchers.Default`. This is what
keeps recomposition cheap and is why formatting must not live in the composable.

---

## 5. UI/UX concept

Design intent: **an operational receiver console that happens to have a map**, not
a flight tracker. Every number is traceable to a pipeline stage. Nothing is shown
as fact that the receiver only inferred.

### 5.1 Navigation map

```
NavigationSuiteScaffold          bar (compact) · rail (medium) · rail+drawer (expanded)
├── live/            Live          ← start destination
│     └── detail/{icao}            bottom sheet (compact) | list-detail pane (medium+)
├── map/             Map
│     └── detail/{icao}            bottom sheet | side pane
├── receiver/        Receiver
├── logs/            Logs
│     └── frame/{id}               raw-frame inspector (full screen)
└── settings/        Settings
      └── settings/{section}       list-detail on medium+
```

Detail is a **nested destination under both Live and Map**, not a sibling — so
back always returns to the list you came from, and the list keeps its scroll
position and selection. On medium+ widths detail is a pane, and no navigation
occurs at all.

Persistent across all destinations: the **status strip** (§5.3). It is part of the
scaffold, not of any screen, so switching tabs never blinks receiver state.

### 5.2 Top app bar

| Slot | Content | Behaviour |
|---|---|---|
| Leading | Tuner chip — `R828D` when attached, `NO SDR` when not **[1-src]** | tap → Receiver screen |
| Title | Destination name; on Live also `N aircraft` | — |
| Action 1 | **Start/Stop** — filled tonal button, label `START` / `STOP` | Stop asks for confirmation only while a source is Running |
| Action 2 | Overflow: Reconnect source · Export diagnostic bundle · Reset session counters · About |

The start/stop control is a **labelled button, not an icon**. A ⏻ glyph alone is
ambiguous about current state, and this control stops data collection.

### 5.3 Persistent receiver-status strip

One line, 40 dp, directly under the app bar, always visible.

```
┌────────────────────────────────────────────────────────────────────────┐
│ ● RUNNING   R828D 44.5 dB   02:14:37   1 284 msg/s   CRC 34.6%   AC 12 │
└────────────────────────────────────────────────────────────────────────┘
```

With one source, the strip's second field is freed from naming the source and
carries **tuner + current gain** instead — the two facts that actually change what
you receive. **[1-src]**

| State | Dot | Fill | Icon | Text |
|---|---|---|---|---|
| Running | ● solid | Success 12 % | — | `RUNNING` |
| Starting | ◐ pulsing | Primary 12 % | — | `STARTING` |
| No signal (device present, no frames) | ◍ | Warning 14 % | ⚠ | `NO SIGNAL` |
| No dongle attached | ○ hollow | Idle | ⛁ | `NO SDR` |
| Dongle removed while running | ○ hollow | Error 14 % | ⛌ | `SDR UNPLUGGED` |
| Error | ✕ | Error 18 % | ⛌ | `ERROR` + short cause |
| Stopped | ○ hollow | Surface | — | `STOPPED` |

`NO SDR` and `SDR UNPLUGGED` are deliberately distinct: the first is a cold start
with nothing plugged in, the second is a live session that lost its device and is
retrying. Only the second starts the antenna-loss shutdown timer.

State is carried by **glyph + background fill + text label**, so colour is never
the sole channel. Tapping the strip navigates to Receiver. The strip collapses to
`● RUNNING · 1 284/s · AC 12` below 360 dp.

`CRC 34.6%` is the **valid ratio** and is tappable → Receiver screen CRC breakdown.
It is the metric that told us the receiver was broken in Session 7 and it earns a
permanent slot.

### 5.4 Screen 1 — Live (start destination)

**Component hierarchy**

```
LiveScreen
├─ MetricsHeader                  collapsible, remembers state
│   ├─ MetricTile ×6              frames/s · valid/s · CRC % · aircraft · max range · gain
│   └─ SparklineRow               60 s frames/s history, Canvas
├─ FilterBar                      horizontally scrollable FilterChip row
├─ SortHeader                     column labels, tap to sort, long-press to pick columns
└─ LazyColumn(key = icao)         ← stable key; NEVER index
    └─ AircraftRow ×N
```

**Row anatomy** (compact, 3 lines, 72 dp):

```
 line 1   A4B1C2  UAL2184   B738          ↑ FL350    442 kt
 line 2   N38901  United Airlines         LAX → EWR ~
 line 3   14.2 mi  073°   ▂▄▆   126 msgs   2s
```

- `↑ FL350` — vertical-rate arrow doubles as the climb/descend indicator; `→` level.
- `~` / `+` route-source markers preserved from the CLI (`~` fallback, `+` live).
  Rendered as a superscript glyph **with a content description**, since the CLI's
  meaning is not self-evident.
- Signal bar `▂▄▆` is a 3-step Canvas bar, not Unicode blocks (font-dependent).
- Age `2s` colours per the CLI rule (≤5 s success, ≤15 s warning, else dim) **and**
  the whole row drops to 60 % alpha past 15 s — redundant encoding.
- TCAS RA active: full-width **Error-tinted leading edge bar** + `RA` badge,
  replacing the CLI's `!` ICAO prefix (which corrupted the ICAO field).

**Sort** — nearest first (default, matching current Android), then: last seen,
altitude, callsign, message count, signal. Sort is a `Sort` enum in `UiState`,
applied in the repository, not the composable.

**Quick filters** — only those the data actually supports:

| Chip | Predicate | Enabled when |
|---|---|---|
| Airborne | `on_ground == false` | always |
| On ground | `on_ground == true` | always |
| Has position | `lat != null` | always |
| Emergency | `squawk in {7500,7600,7700}` \|\| `tcas_ra_active` | always |
| Within N mi | `distance <= N` | observer position known |
| Above/below FL | altitude band | always |

**Military / unknown-operator is NOT offered.** The receiver has no reliable
military indicator; ICAO-range heuristics are folklore. Omitted deliberately.

**Empty / non-nominal states** — each is a centred `StatusPanel(icon, headline,
body, primaryAction)`:

| Condition | Headline | Action |
|---|---|---|
| Running, 0 aircraft, <30 s | `Listening…` | — (spinner) |
| Running, 0 aircraft, >30 s, frames>0 | `Receiving frames, no valid decodes` | Open Receiver |
| Running, 0 frames, >30 s | `No signal` | Check antenna / Open Receiver |
| No USB device | `Plug in your SDR dongle` — illustration of the OTG adapter | Retry **[1-src]** |
| Dongle removed mid-session | `SDR unplugged — retrying` + countdown to auto-stop | Stop now |
| Driver app missing | `RTL-SDR driver app not installed` | Install |
| Stopped | `Receiver stopped` | Start |
| Error | `Receiver error` + cause | Reconnect · View logs |

### 5.5 Screen 2 — Map

- **osmdroid**, offline tile cache, observer-centred on first fix.
- Aircraft glyph: a triangle **rotated to `track_deg`**; when track is unknown the
  glyph is a **circle** (never a triangle pointing at a guessed heading).
- Marker states — each differs in **shape or fill, not only colour**:

| State | Glyph |
|---|---|
| Airborne, fresh | filled triangle, track-rotated |
| On ground | filled square |
| No track | filled circle |
| Stale (>15 s) | hollow outline, 50 % alpha |
| Selected | +40 % size, accent ring, label always shown |
| Emergency / RA | filled triangle + pulsing ring + always-on label |

- Labels: callsign + altitude, shown above `zoom >= 9` or when selected; collision-
  suppressed by a simple grid hash.
- Trails: last N positions per aircraft, `Polyline`, off by default, N configurable
  (0/10/50/200). Memory-bounded per aircraft.
- Observer: distinct ⌂ marker + **range rings** at 25/50/100/150 mi (unit follows
  the Settings unit choice), labelled on the ring itself.
- Clustering: above 150 markers, positions below zoom 8 collapse to count bubbles.
  Above 400 markers the map switches to a **decimated draw** (every marker still
  tracked, only rendering thinned) and shows a `showing 400 of N` chip — never a
  silent cap.
- Controls, bottom-right vertical stack: follow-observer toggle · recenter ·
  layers (map style / trails / labels / rings) · range-scale stepper.
- **Decoupling requirement:** the map subscribes only to `mapAircraft` at 2 Hz and
  holds no reference to the pipeline. Map redraw cost cannot affect reception
  because reception runs in the service on its own thread; this must be asserted in
  a Phase 6 test that pans/zooms continuously for 5 min while measuring msg/s.

### 5.6 Screen 3 — Aircraft detail

Bottom sheet (compact, 3 detents: peek 120 dp / half / full) or side pane (medium+).
Sections, in order:

1. **Identity** — ICAO (always, locally decoded) · callsign (decoded) · registration
   / type / operator each with a **provenance chip**: `decoded` · `algorithmic`
   (ICAO→N-number) · `enriched (adsbdb)` · `cached 3 h ago`. Enriched values are
   never shown without their chip.
2. **State** — position, altitude (baro/GNSS labelled), vertical rate, ground speed
   / airspeed (labelled which), track, heading, squawk (with emergency name if
   7500/7600/7700), category, on-ground, range, bearing.
   Every value carries a **freshness dot** (§5.9) and, on tap, `updated 4 s ago
   from DF17 TC11`.
3. **Why is X missing?** — a direct port of `ui/summary.py:_diagnose()`, the single
   best diagnostic feature in the CLI. Verbatim semantics:
   - `Callsign missing — no TC 1–4 messages received yet`
   - `Position missing — 7 even CPR frames, no odd frame yet`
   - `Position missing — 4 even + 3 odd received, pair decode failed (frames too far apart)`
   - `Altitude missing — 12 altitude msgs received but AC field decoded as None`
   Shown only when the field is absent. This section is what makes the app a
   receiver console rather than a tracker.
4. **Reception** — messages total / valid / corrected / recovered / bad, per-DF
   histogram, signal sparkline, II codes, first seen, last seen.
5. **TCAS** — only when `tcas_sl != null || tcas_event_count > 0`. SL + name, RA
   text, complement, intruder ICAO, event count, terminated state.
6. **Message timeline** — reverse-chronological, virtualised, default 50:
   ```
   14:22:07  DF17 TC11 Airborne position (baro)      [VAL]
             8D4840D6 58C382D6 90C8AC28 63A7
             alt=35 000 ft  cpr_even=(93000,51372)
   ```
   Row is collapsed to line 1 by default; tap expands hex + fields. Hex is rendered
   as **plain `Text` with a monospace token**, never a per-character composable —
   see §5.10.
7. **Raw-frame inspector** (full-screen, from a timeline row) — hex with byte
   groups, bit-level field overlay (DF / CA / ICAO / ME / PI), computed CRC, CRC
   status and, for `recovered`, the resolving cache entry. Copy-to-clipboard.

### 5.7 Screen 4 — Receiver

```
ReceiverScreen
├─ DeviceCard            state · tuner name · gain-step count · USB permission · driver app version ·
│                        uptime · start/stop/reconnect     ← replaces the old "which source?" card
├─ ConfigCard            gain (auto/manual + level) · sample rate (read-only 2.0 Msps + why) ·
│                        frequency (read-only 1090 MHz) · PPM · bias-tee
├─ TuningCard            ← ports the CLI's live RF knobs
│   ├─ Slider  Preamble gap divisor   3 … 12    (current 6)
│   ├─ Slider  Delta floor          255 … 5100  (step 255, current 2550)
│   ├─ WindowStepper  Accept-rate window  5…60 s
│   └─ AcceptRatePanel   "Last 10 s: 4 812 tested · 1 664 accepted (34.6%) · 3 148 rejected"
│                        + Reset to defaults.  Changing a knob resets the window.
├─ PipelineCard          per-stage counters, top to bottom:
│     USB read        4.2 MB/s     buffers 64/s     overruns 0
│     Demod           preamble candidates 18 402/s  frames 1 284/s
│     CRC             valid 441  corrected 12  recovered 38  unresolved 91  bad 702
│     Decode          decoded 491  undecodable 0    dropped (backpressure) 0
│     State           aircraft 12  new this minute 3  expired 1
├─ RateChart            60 s stacked area: valid / corrected / recovered / bad
├─ CoverageCard         polar plot, 8 sectors × median range, symmetry score 0–100,
│                       best/worst sector, altitude-band bars   (from coverage.py)
└─ GuidanceCard         evidence-tied hints (below)
```

**Guidance rules** — each states the observation, then the inference, and never
claims more than the evidence supports:

| Observed | Message |
|---|---|
| frames > 0, valid = 0, > 30 s | `Frames are being found but none pass CRC. This usually means the sample rate or demodulator tuning is wrong — not the antenna.` |
| frames = 0, > 30 s, source Running | `No preamble candidates. Check the antenna connection and gain.` |
| valid ratio < 5 %, gain at table max | `Accept rate is low at maximum gain. Try a lower gain — front-end overload also lowers accept rate.` |
| overruns > 0 | `Samples are being dropped before demodulation. The device is producing data faster than it is consumed.` |
| symmetry < 40, ≥ 20 min data | `Range is uneven across sectors — best {X} ({a} mi), worst {Y} ({b} mi). This is consistent with an obstruction or a directional antenna; it is not by itself proof of either.` |
| Follow GPS on, no fix > 2 min | `No GPS fix yet — range and bearing use the fixed coordinates.` |

No message asserts an RF conclusion (cable loss, SWR, interference) that the app
cannot observe.

### 5.8 Screen 5 — Logs / diagnostics

- Structured `DiagnosticEvent(timestamp, category, severity, message, detail?)`,
  ring buffer 2 000 in memory, persisted to Room when developer diagnostics is on.
- Categories, each independently filterable, mapped to a distinct icon:
  `OPERATIONAL` · `SOURCE` (USB/network lifecycle) · `DECODER` (CRC/decode
  anomalies) · `LOCATION` · `APP_ERROR`.
- Local timestamps to the second, UTC on tap (mirrors `csv_writer.py`'s dual-stamp).
- Severity: debug/info/warn/error, filter chips, error count badge on the tab.
- Search across message text; filters and search survive rotation (`UiState`).
- **Unresolved-frame retention** (opt-in, developer diagnostics): `parity_addr` and
  `bad` frames kept in a bounded Room table with hex + DF + timestamp + signal, for
  later analysis; capped by row count *and* age, with the current size shown and a
  one-tap purge.
- **Export diagnostic bundle** — direct port of `debug_bundle.py`: version, config
  (redacted), stats snapshot, event log, per-DF counters, recent frames, optional
  IQ ring buffer. Privacy defaults: **observer coordinates and IQ excluded unless
  explicitly ticked**, each with a plain-language note of what including it reveals.
  Export goes through the share sheet; the app never uploads.

### 5.9 Screen 6 — Settings

Grouped, searchable (`SearchBar` filtering across titles, summaries and keywords),
list-detail on medium+. Every row shows an inline one-line explanation, and every
row that forces a reconnect carries a **`RESTARTS RECEIVER` chip**.

**[1-src]** The Source group is deleted. `ppmCorrection` is now the **only**
setting in the entire app that restarts the receiver, so the `RESTARTS RECEIVER`
chip appears exactly once — which makes it worth reading rather than wallpaper.

| Group | Settings | Restart? |
|---|---|---|
| Tuner | auto/manual gain · gain level · PPM · bias-tee | gain/bias-tee **no** (live control channel) · PPM **yes** |
| Decoder | single-bit CRC correction · decode DF families · CPR max pair age · aircraft expiry | no |
| Demod tuning | preamble gap divisor · delta floor · reset to defaults | no |
| Display | units (mi/nm/km) · theme (dark/light/system) · columns · row density · max rows · keep screen on | no |
| Map | tile source · offline cache size + purge · trails length · labels · range rings · clustering threshold | no |
| Location | Fixed vs **Follow GPS** · fixed coordinates · GPS refresh interval (off/15/30/60/120/360 min) · permission state | no |
| Power | **antenna-loss shutdown** (off/1/5/10/15/30 min) · behaviour on screen off | no |
| Logging & storage | raw message log · unresolved-frame retention · history retention days · storage used + purge · export CSV | no |
| Enrichment | route lookup on/off · registration lookup on/off · cache TTL · clear cache (each labelled *requires network*) | no |
| Developer | verbose logging · per-stage counters · IQ capture for bundles · show internal IDs | no |

Dark theme is the **default**; light and system are offered. Rationale: the tool is
used at night and outdoors, and the CLI it replaces is dark.

### 5.10 Text wireframes

**Compact phone portrait — Live (411 × 891 dp)**

```
╔══════════════════════════════════════════╗
║ R828D  Live · 12 aircraft       [STOP] ⋮ ║
╠══════════════════════════════════════════╣
║ ● RUNNING 44.5dB 02:14:37 1284/s 34.6%   ║
╠══════════════════════════════════════════╣
║ ┌──────┬──────┬──────┐  ▁▂▄▆▇▆▄▂▁▂▄▆    ║
║ │1284/s│ 34.6%│ 12 AC│  frames/s · 60 s  ║
║ │frames│ valid│      │                   ║
║ └──────┴──────┴──────┘              ⌃    ║
╠══════════════════════════════════════════╣
║ (Airborne)(Position)(Emergency)(<50mi) ▸ ║
╠══════════════════════════════════════════╣
║ Dist ▲ │ ICAO  CALLSIGN  ALT  SPD   AGE  ║
╟──────────────────────────────────────────╢
║ A4B1C2  UAL2184   B738      ↑FL350 442kt ║
║ N38901  United Airlines     LAX→EWR ~    ║
║ 14.2mi  073°  ▂▄▆   126 msgs        2s   ║
╟──────────────────────────────────────────╢
║ ▌AC0DEF  SWA1188  B38M      →FL280 389kt ║  ← RA edge bar
║  N8703M  Southwest          RA           ║
║  22.7mi  201°  ▂▄▄   88 msgs        1s   ║
╟──────────────────────────────────────────╢
║  3C6444  DLH441   A359      ↓FL180 301kt ║
║  D-AIXA  Lufthansa          FRA→LAX +    ║
║  41.0mi  318°  ▂▃    54 msgs       12s   ║
╟──────────────────────────────────────────╢
║  (dimmed, 60% alpha, >15 s)              ║
║  A91B03  —        —         —      —     ║
║  —       —                  —            ║
║  —       —     ▂     9 msgs        23s   ║
╠══════════════════════════════════════════╣
║  ▤ Live   ◎ Map   ⚙ Recv  ☰ Logs  ⚙ Set ║
╚══════════════════════════════════════════╝
```

**Compact phone portrait — detail sheet at half detent**

```
╔══════════════════════════════════════════╗
║ (list still visible, dimmed)             ║
╟────────────────  ▁▁▁  ───────────────────╢
║ A4B1C2   UAL2184                      ✕  ║
║ N38901 ·algorithmic·  B738 ·adsbdb 3h·   ║
╟──────────────────────────────────────────╢
║ ● 35 000 ft baro    ● 442 kt GS          ║
║ ● 073° track        ● ↑ +1 216 fpm       ║
║ ◐ 33.7421°, −117.9  ● 1200 squawk        ║
║ ● 14.2 mi  073°     ○ heading —          ║
╟──────────────────────────────────────────╢
║ ⚠ Heading missing — no TC 19 velocity    ║
║   messages carrying magnetic heading     ║
╟──────────────────────────────────────────╢
║ 126 msgs · 118 valid · 4 corrected       ║
║ DF17 ▇▇▇▇▇▇  DF11 ▇▇  DF4 ▇              ║
╟──────────────────────────────────────────╢
║ TIMELINE                              ⌄  ║
║ 14:22:07 DF17 TC11 Airborne pos   [VAL]  ║
║ 14:22:06 DF17 TC19 Velocity       [VAL]  ║
║ 14:22:05 DF11      All-call       [VAL]  ║
╚══════════════════════════════════════════╝
```

`●` fresh · `◐` ageing · `○` stale/absent — see §5.11.

**Phone landscape (891 × 411 dp) — Live**

Rail replaces the bottom bar; the metrics header auto-collapses to a single line;
detail opens as a right pane at 45 % width when a row is selected.

```
╔═╤════════════════════════════════════════════════════════╤═══════════════════╗
║▤│ R828D  Live · 12                           [STOP]   ⋮  │ A4B1C2  UAL2184 ✕ ║
║◎│ ● RUNNING 44.5dB 02:14:37 1284/s 34.6% AC12            │ N38901 · B738     ║
║⚙│ (Airborne)(Position)(Emergency)(<50mi)                 │───────────────────║
║☰│ Dist▲ ICAO   CALLSIGN  ALT     SPD    HDG   MSG   AGE  │ ● 35 000 ft baro  ║
║⚙│ 14.2  A4B1C2 UAL2184   ↑FL350  442kt  073°  126   2s   │ ● 442 kt GS       ║
║ │ 22.7  AC0DEF SWA1188   →FL280  389kt  201°   88   1s   │ ◐ 33.74, −117.93  ║
║ │ 41.0  3C6444 DLH441    ↓FL180  301kt  318°   54  12s   │ ⚠ Heading missing ║
║ │ 55.1  A91B03 —         —       —      —       9  23s   │ TIMELINE       ⌄  ║
╚═╧════════════════════════════════════════════════════════╧═══════════════════╝
```

**Tablet / expanded (1280 × 800 dp) — Live, three panes**

```
╔══╤═══════════════════════════════════════════════╤══════════════════════════╗
║  │ R828D   Live                     [STOP]    ⋮  │                          ║
║▤ ├───────────────────────────────────────────────┤  A4B1C2   UAL2184        ║
║◎ │ ● RUNNING 44.5dB 02:14:37 1284/s 34.6% AC 12  │  N38901 ·algorithmic·    ║
║⚙ ├──────┬──────┬──────┬──────┬──────┬───────────┤  B738 ·adsbdb 3 h·       ║
║☰ │1284/s│34.6% │  12  │68.2mi│ 44.5 │ ▁▂▄▆▇▆▄▂ │──────────────────────────║
║⚙ │frames│ valid│  AC  │ max  │ gain │  60 s     │ ● 35 000 ft (baro)       ║
║  ├──────┴──────┴──────┴──────┴──────┴───────────┤ ● 442 kt ground speed    ║
║  │(Airborne)(Ground)(Position)(Emergency)(<50mi)│ ● 073° track             ║
║  ├───────────────────────────────────────────────┤ ● ↑ +1 216 fpm          ║
║  │Dist▲ ICAO  REG    CALL   TYPE  ALT   SPD  AGE│ ◐ 33.7421°, −117.9312°   ║
║  │14.2 A4B1C2 N38901 UAL2184 B738 FL350 442  2s │ ● squawk 1200            ║
║  │22.7 AC0DEF N8703M SWA1188 B38M FL280 389  1s │ ○ heading —              ║
║  │41.0 3C6444 D-AIXA DLH441  A359 FL180 301 12s │──────────────────────────║
║  │55.1 A91B03 —      —       —    —     —   23s │ ⚠ Heading missing — no   ║
║  │                                               │   TC 19 velocity msgs    ║
║  │                                               │──────────────────────────║
║  │                                               │ 126 msgs · 118 valid     ║
║  │                                               │ DF17 ▇▇▇▇▇ DF11 ▇▇ DF4 ▇ ║
║  │                                               │──────────────────────────║
║  │                                               │ TIMELINE              ⌄  ║
╚══╧═══════════════════════════════════════════════╧══════════════════════════╝
```

**Tablet — Receiver**

```
╔══╤═══════════════════════════════════════════════════════════════════════════╗
║▤ │ Receiver                                          [STOP]  [RECONNECT]  ⋮  ║
║◎ ├──────────────────────────────┬────────────────────────────────────────────╢
║⚙ │ STATUS                       │ PIPELINE                                   ║
║☰ │ ● Running        02:14:37    │ USB read   4.2 MB/s   64 buf/s  overrun 0  ║
║⚙ │ R828D · 29 gain steps · OTG  │ Demod      18 402 cand/s   1 284 frames/s  ║
║  │ 2.0 Msps · 1090.000 MHz      │ CRC        val 441 cor 12 rec 38 bad 702   ║
║  │ Gain  44.5 dB (manual)       │            unresolved 91                   ║
║  ├──────────────────────────────┤ Decode     491 ok · 0 undecodable          ║
║  │ TUNING                       │ State      12 aircraft · +3 · −1 expired   ║
║  │ Gap divisor  ├──●──────┤  6  │ Backpressure drops  0                      ║
║  │ Delta floor  ├────●────┤2550 ├────────────────────────────────────────────╢
║  │ Window       ├─●──────┤ 10 s │ RATE — 60 s     ▇ valid ▨ corr ▧ rec ░ bad ║
║  │ Last 10 s:                   │ ░░▨▇▇▇░░▇▇▧▇▇░░▇▇▇▇░▇▇▧▇▇░░▇▇▇▇▇░▇▇▇▨▇▇   ║
║  │  4 812 tested                ├────────────────────────────────────────────╢
║  │  1 664 accepted (34.6%)      │ COVERAGE          symmetry 62 / 100        ║
║  │  3 148 rejected              │         N 51mi                             ║
║  │  [Reset to defaults]         │    NW 44    ╱│╲    NE 68  ← best           ║
║  ├──────────────────────────────┤     W 22 ──┼─┼── E 61                      ║
║  │ ⓘ Range is uneven across     │    SW 19    ╲│╱    SE 40                   ║
║  │ sectors — best NE (68 mi),   │       ↑ worst  S 31                        ║
║  │ worst SW (19 mi). Consistent │  <3k ▇▇  3–10k ▇▇▇▇▇  10–30k ▇▇▇  >30k ▇▇▇▇║
║  │ with an obstruction or a     │                                            ║
║  │ directional antenna; not by  │                                            ║
║  │ itself proof of either.      │                                            ║
╚══╧══════════════════════════════╧════════════════════════════════════════════╝
```

### 5.11 Design tokens

**Spacing** — 4 dp base. `xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32`.
Screen gutter 16 dp compact / 24 dp medium / 24 dp expanded. Card padding 16 dp.
Dense list row 72 dp (3-line) or 56 dp (2-line, "compact rows" setting).

**Typography** — M3 scale; the receiver-specific additions:

| Token | Use | Spec |
|---|---|---|
| `displayMetric` | metric tile numbers | 28 sp / 32 lh / w600 / tabular figures |
| `titleMedium` | row primary (callsign, ICAO) | M3 default, w600 |
| `bodyMedium` | row secondary | M3 default |
| `labelSmall` | units, chips, age | M3 default, 11 sp floor |
| `dataMono` | ICAO, hex, squawk, coordinates | `FontFamily.Monospace`, tabular, 13 sp |

**All numeric columns use tabular figures** (`FontFeatureSetting("tnum")`). Without
it, digits change width every tick and a dense table visibly jitters at 4 Hz.

**Semantic colours** (dark, the operational default; light theme derives at ≥4.5:1
body / ≥3:1 large):

| Token | Dark | On-contrast | Meaning |
|---|---|---|---|
| `background` | `#0A0E13` | — | app background |
| `surface` | `#141A21` | — | cards, sheets |
| `surfaceElevated` | `#1E2730` | — | inputs, menus |
| `outline` | `#3D4A57` | — | borders, dividers |
| `primary` | `#38BDF8` | `#00131C` | selection, links, actions |
| `textPrimary` | `#E6EDF3` | — | ~15:1 on background |
| `textSecondary` | `#A9B7C6` | — | ~8:1 on surface |
| `statusOk` | `#4ADE80` | `#00210E` | running, valid, fresh |
| `statusWarn` | `#FFB74D` | `#231400` | ageing, corrected, degraded |
| `statusError` | `#FF6B6B` | `#2C0000` | error, bad CRC, emergency |
| `statusInfo` | `#38BDF8` | `#00131C` | recovered, informational |
| `statusIdle` | `#6B7A8A` | — | stopped, unknown, absent |

Existing `AdsbColors` already matches this palette — carry it forward unchanged.

**Colour is never the only channel.** Every status also carries a glyph, and every
status region also carries a text label or a fill tint. The Live row uses alpha
*and* colour for age; the map uses shape *and* colour for state; the CRC breakdown
uses labelled segments *and* colour.

**Icon rules** — Material Symbols outlined, 24 dp (20 dp inline). Fixed
assignments, never reused for another meaning: `usb` source · `wifi` network ·
`folder` file · `science` demo · `radar` receiver · `map` map · `list` live ·
`terminal` logs · `tune` settings · `warning` degraded · `error` failed ·
`check_circle` ok · `schedule` stale · `sos` emergency. Status glyphs in dense
rows are drawn text glyphs (`●◐○▌`) sized to the row, not icon composables.

**Motion** — deliberately restrained; this is an instrument.

| Element | Rule |
|---|---|
| Numeric values | **no animation**. A counter that tweens is unreadable and lies about the current value |
| Row insert/remove | `animateItem()`, 150 ms fade + 100 ms size; **no slide** — slides look like re-sorts |
| Sort change | 200 ms `animateItem()` reorder, only on explicit user sort |
| Sheet / pane | M3 defaults |
| Status change | 150 ms colour crossfade |
| `STARTING` pulse | 1 s, only in that state |
| Emergency ring | 1.2 s pulse; **the only looping animation in the app** |
| Charts | new samples appended without redrawing history |
| Reduced motion | honour `Settings.Global.ANIMATOR_DURATION_SCALE == 0` → all of the above become instant, emergency pulse becomes a static double ring |

### 5.12 Data freshness / staleness rules

Freshness is a **property of each field**, derived from the age of the message that
last wrote it — not of the aircraft. Ported from `last_position_ts` and generalised.

| Tier | Age since that field was written | Rendering |
|---|---|---|
| `FRESH` | ≤ 5 s | `●` `statusOk`, full opacity |
| `AGEING` | 5–15 s | `◐` `statusWarn`, full opacity |
| `STALE` | > 15 s | `○` `statusIdle`, value at 60 % alpha |
| `EXPIRED_FIELD` | > 60 s but aircraft alive | value replaced by `—`, tooltip `last known 1 m 40 s ago: 35 000 ft` |
| `ABSENT` | never received | `—`, `statusIdle`, tap → the `_diagnose()` explanation |

Per-field thresholds where physics differs:

| Field | AGEING | STALE | Why |
|---|---|---|---|
| Position | 5 s | 15 s | ~2 Hz nominal |
| Altitude, vertical rate | 5 s | 15 s | ~2 Hz |
| Speed, track | 5 s | 20 s | TC 19 is less frequent |
| Callsign, registration, type, category | never | never | identity does not decay |
| Squawk | 30 s | 120 s | changes rarely, DF5/21 are sparse |
| Signal, message count | 5 s | 15 s | continuous |
| Range, bearing | inherits from position | | derived |

Rules that follow:
- **A derived value is never fresher than its least-fresh input.** Range shows the
  position's freshness.
- **The aircraft's own age is `max` of the field ages**, i.e. time since the last
  message of any kind — this is the Age column and matches the CLI exactly.
- On the map, `STALE` switches the glyph to a hollow outline; `EXPIRED_FIELD`
  position removes the marker but keeps the aircraft in the list.
- Expiry removes the aircraft entirely at `aircraftExpirySeconds` (60 s default) and
  moves it to history — matching `purge_expired()`.

### 5.13 High-update-rate UX rules

Non-negotiable; these are the difference between a 4 Hz table that scrolls at 120 fps
and one that stutters.

1. **Fixed-rate emission, never per-message.** The repository emits on a 4 Hz tick
   (2 Hz map, 1 Hz metrics). The pipeline updates a mutable snapshot; the ticker
   publishes it. `sample(250.ms)` on the pipeline flow is the fallback if a
   pipeline-driven flow is ever used directly.
2. **Diff before emit.** `distinctUntilChanged` with a structural comparator on the
   list; if nothing a row displays has changed, no new list is emitted. A
   fast-changing field the UI doesn't show must not cause an emission.
3. **Stable keys.** `items(rows, key = { it.icao })`. Index keys are forbidden —
   they defeat `animateItem` and force full rebinds when the sort order changes.
4. **Per-row change isolation.** `AircraftRow` takes an `@Immutable` data class.
   Any change to any row must not recompose siblings — verified by a
   `Modifier.recomposeHighlighter` pass and by a Compose-compiler-metrics check in
   CI that all row parameters are stable.
5. **Format off the UI thread.** All strings (altitude with thin-space grouping,
   `FL350`, `14.2 mi`, `073°`, `2s`) are produced in the repository mapper on
   `Dispatchers.Default`. No `String.format` inside a composable.
6. **No lambda allocation per row.** Callbacks come from a `@Stable` `LiveActions`
   interface (the ViewModel implements it), not from `{ vm.onClick(it) }` created
   per item.
7. **Map throttling.** 2 Hz marker updates; positions moved via marker mutation, not
   overlay rebuild; label collision computed on `Dispatchers.Default`; the map is
   detached from its flow when its destination is not resumed.
8. **Charts append, not rebuild.** Fixed-size ring buffers behind `Canvas`; only the
   newest sample is drawn per frame.
9. **Raw frames are never composed per byte.** Hex is a single pre-built
   `AnnotatedString` computed off-thread and cached per frame id. Timeline rows are
   collapsed to one line until expanded, and the timeline is virtualised.
10. **Backpressure is visible.** The state channel drops oldest under overload and
    the dropped count is a Receiver-screen counter. No silent loss.
11. **Off-screen means unsubscribed.** Every screen collects with
    `collectAsStateWithLifecycle`; `selected` is only computed while a detail
    surface is open.
12. **Reception never depends on the UI.** All of the above lives above the service
    boundary. Phase 6 asserts msg/s is unchanged with the UI foregrounded, scrolling,
    backgrounded, and with the screen off.

### 5.14 Accessibility

- Touch targets ≥ 48 dp. Dense rows are 72/56 dp tall and full-width tappable;
  in-row controls that would fall below 48 dp are removed rather than shrunk.
- Every status glyph, sparkline, chart, map marker and provenance chip has a
  `contentDescription`. Charts additionally expose a text summary
  (`stateDescription`) — "valid 34.6 percent, trending up over the last minute".
- Row semantics are **merged into one announcement**: "United 2184, Boeing 737-800,
  flight level 350, 442 knots, 14.2 miles, bearing 073, updated 2 seconds ago".
  Individual cells are not separately focusable.
- Font scaling to **200 %** without loss: dense rows reflow to a 2-line stacked
  layout above 130 % scale; tables become cards above 160 %. No fixed `sp` heights;
  no `maxLines = 1` on any user-meaningful text without an accompanying full value
  in semantics.
- Contrast: body text ≥ 4.5:1, large/graphical ≥ 3:1, status text on tinted fills
  verified per pair. The dark palette in §5.11 already exceeds 7:1 for body text.
- Colour-blind safe: every status distinguishable by glyph and/or shape alone;
  the CRC chart segments are labelled and additionally patterned.
- Keyboard/D-pad: full traversal, visible focus ring, and — as a nod to the CLI —
  `↑ ↓` move selection, `Enter` opens detail, `Esc` closes it, when a hardware
  keyboard is attached.
- `keepScreenOn` is a setting, default **off**, because the default is a
  battery-sensitive field tool.

### 5.15 Offline and privacy behaviour

- **The receiver is fully functional offline.** Capture, demod, CRC, decode, state,
  map (cached tiles), history, logging and export require no network.
- Network is used only by: route lookup, registration/type lookup, and map tile
  download. Each is an independent, individually revocable setting; each is
  labelled *requires network*; all fail silently to "not enriched" and never block
  or degrade decoding.
- **Enrichment defaults to off** — matching the current `enrichmentEnabled = false`.
- Only the ICAO or callsign leaves the device for enrichment; never position,
  never observer location, never a device identifier. Batch/coalesce lookups, at
  most one in flight per ICAO.
- Observer coordinates never leave the device. In exports they are excluded by
  default and the include-toggle states plainly what it reveals.
- No analytics, no crash reporting to a third party, no ads, no account.
- Location permission requested only when Follow GPS is selected; denial is a
  silent fall back to fixed coordinates. No background-location permission.
- Diagnostic bundles are shared via the system share sheet — the user chooses the
  destination; the app never uploads.

### 5.16 CLI capability → Android UI mapping

Every display capability of `ui/summary.py`. `⏸` = intentionally deferred.

| CLI capability | Source | Android destination |
|---|---|---|
| Version + local time + runtime (row 1) | `_render` | Status strip (uptime) · About (version) |
| Active / historic counts (row 2) | `_render` | Status strip · Logs → History |
| Total / valid / recovered / unresolved / noise / msg-s (row 2) | `_render` | Live metrics + Receiver CRC breakdown |
| Observer coords + distance units (row 2) | `_render` | Settings → Location · Settings → Display |
| `--source` selection (rtlsdr/file/network/dummy/textfile) | `cli.py` | ⏸ **Dropped [1-src]** — the antenna is the only source; replay lives in the test harness |
| rx dist max / mean (row 2) | `_render` | Live metric tile "max range" · Receiver coverage |
| Gap divisor + delta floor (row 3) | `_render` | **Receiver → Tuning sliders** |
| Rolling window tested/accepted/rejected (row 3) | `_render` | **Receiver → Accept-rate panel** |
| Keyboard hint line (row 4) | `_render` | Removed — gestures/controls are visible affordances |
| `▶ Active Aircraft` table | `_active_row` | Live list |
| — ICAO | | Live row 1 · Detail identity (mono) |
| — Registration | `enrich` | Live row 2 · Detail identity + provenance chip |
| — Callsign | | Live row 1 |
| — Aircraft Type (34 ch) | `enrich` | Live row 1 (code) · Detail identity (full name) |
| — Airline (22 ch) | `enrich` | Live row 2 |
| — Route + `+`/`~` marker | `_route_with_marker` | Live row 2 with provenance glyph |
| — Alt / Spd / Hdg / V-S | | Live row 1 (`↑FL350`, `442 kt`) · Detail state |
| — Lat / Lon | | Detail state (dropped from the compact row; on the map instead) |
| — Distance | `distance_mi` | Live row 3 |
| — Signal bar (Unicode blocks) | `_signal_bar` | Live row 3 Canvas bar · Detail sparkline |
| — Msgs | | Live row 3 · Detail reception |
| — Age + 5 s/15 s colour rule | `_age_colour` | Live row 3 + row alpha (§5.12) |
| — II Codes `"3;7"` | | Detail reception |
| — `!` prefix on TCAS RA | `_active_row` | Live row error edge bar + `RA` badge |
| Stable first-seen row order | `_update_active_order` | Repository sort is explicit; stability guaranteed by ICAO keys |
| Selection highlight + `►` marker | `_render` | Selected row tint + selected pane on medium+ |
| `↑↓` select, `D` detail, `Esc` close | `_handle_keys` | Tap to open; hardware keys mapped identically (§5.14) |
| Detail: `_diagnose()` missing-field explanations | `_diagnose` | **Detail → "Why is X missing?"** — verbatim semantics |
| Detail: TCAS SL / RA / complement / intruder / events | `_diagnose` | Detail → TCAS section |
| Detail: last 20 messages, DF+TC names, CRC tag, hex, fields | `_render_detail` | Detail → timeline + raw-frame inspector |
| Detail: total/valid/bad counts | `_render_detail` | Detail → reception |
| `◼ Historic Data` table | `HistoricTracker` | Logs → History tab (Room-backed, filter + CSV export) |
| Historic CSV export + filename in header | `_csv_file` | Settings → Logging → Export CSV (share sheet) |
| Performance CSV (60 s) | `performance.py` | Receiver live counters + Logs → Export |
| Coverage CSV (300 s, sectors, symmetry) | `coverage.py` | **Receiver → Coverage polar card** + export |
| Debug bundle zip | `debug_bundle.py` | Logs → Export diagnostic bundle |
| Flicker-free ANSI redraw | `_ScreenWriter` | n/a — Compose |
| curses UI (`ui/terminal.py`) | | ⏸ Not ported — superseded by `summary.py`, which is the reference |
| FastAPI web console | `web/server.py` | ⏸ Not ported — see §4.1 |
| FlightAware scrape + `+` marker semantics | `enrich/flightaware.py` | ⏸ Dropped; `~` (adsbdb) retained |
| `--json` / `--print` message streams | `output/emitter.py` | ⏸ Deferred to Phase 5 export |
| Nominatim address geocoding | `enrich/location.py` | ⏸ Deferred; Android `Geocoder` in Phase 5 |
| Mode A/C decode (`decode_mode_ac`) | flag exists, unimplemented | ⏸ Not implemented in Python either |
| MLAT (`enable_mlat`) | reserved flag | ⏸ Out of scope |

---

## 6. Phased migration roadmap

Common to every phase: JVM unit tests must pass; no phase merges with a regression
in the parity suite; rollback is `git revert` of that phase's commits, since each
phase is self-contained and the previous phase remains shippable.

### Phase 0 — Baseline and behaviour capture

**Scope** — freeze the reference. No Kotlin changes.
**Modules** — `docs/`, `core-test/src/test/resources/fixtures/`.
**Work**
- Copy `adsb_v9_5` to a read-only `reference/python/` snapshot inside the Android
  repo (or record its hash + path if it must stay external).
- Capture fixtures: `tests/fixtures/modes1.bin` (IQ), `recordings/adsb_2026062{1,2}.txt`
  (AVR), plus a new ≥ 10-minute live IQ capture and its matched AVR output.
- Run the Python suite, record the 169-test result and version.
- Generate the golden reference: for every frame in every fixture, run Python and
  emit `frame_hex, df, crc_status, remainder, corrected_bit, recovered_icao,
  decoded_icao, callsign, alt_baro, alt_gnss, lat, lon, gs, track, heading, vs,
  squawk, tc, cpr_fmt, cpr_lat, cpr_lon, ii_code, tcas_*` as TSV.
- Generate an aircraft-state golden: replay each fixture through Python and dump
  the full `AircraftState` table at fixed 10 s checkpoints.

**Dependencies** — none.
**Acceptance** — golden files exist, regenerate byte-identically on a second run,
and are committed. Python suite result recorded.
**Performance target** — n/a.
**Rollback** — n/a (additive).

### Phase 1 — Domain models, module split, CRC/decoder parity

**Scope** — collapse the `:app` / `:core-test` duplication; lock decoder parity.
**Modules** — new `:core:model`, `:core:receiver`; `:app` depends on them;
`:core-test` deleted.
**Work**
- Move `demod`, `crc`, `decoder`, `aircraft`, and pure parts of `capture` into
  `:core:receiver`; models into `:core:model` (zero Android deps).
- Delete the duplicated copies. **This is the highest-value change in the plan** —
  it is why a decoder fix currently has to be made twice.
- **[1-src] Delete the non-antenna sources**: `NetworkSource`, `DummySource`,
  `RtlTcpSource`, `SourceType`, `NetworkFormat`, `AppConfig.{sourceType,
  networkHost, networkPort, networkFormat, filePath}` and their persistence in
  `AppConfigStore`. Move `FileSource` and a new AVR `TextFileSource` into the
  **test** source set of `:core:receiver`.
- Extend the frame-level parity test to every Phase 0 fixture, field by field.
- Port `_resolve_ap_icao` known-ICAO recovery path.

**Dependencies** — Phase 0 goldens.
**Acceptance**
- Zero duplicated `.kt` files between modules (CI check on file hashes).
- **[1-src]** No reference to a network, dummy or file source survives in any
  `main` source set — asserted by a grep-based CI check, so replay code cannot
  drift back into the shipped app.
- Frame parity: **100 % agreement** on `crc_status`, `remainder`, `recovered_icao`,
  `decoded_icao` and every decoded field, across all fixtures, except the single
  documented DF11-IID divergence and the documented sanity clamps — each asserted
  explicitly by an allow-list test, not by a tolerance.
- Existing 209 tests still pass.

**Parity/replay tests** — `FrameParityTest` (per-field TSV diff), `DemodParityTest`
(same frame set extracted from `modes1.bin` by both implementations).
**Performance** — demod ≥ 4× real time on a Pixel 6 big core, single thread,
measured by JVM benchmark on the IQ fixture.
**Rollback** — module split is mechanical; revert restores the duplicated layout.

### Phase 2 — Aircraft-state parity and replay harness

**Scope** — prove the state machine, not just the decoder.
**Modules** — `:core:receiver` (aircraft), new `:core:receiver` test harness.
**Work**
- `ReplayHarness`: feed a fixture through the full Kotlin pipeline with a
  **virtual clock**, dump the aircraft table at the same 10 s checkpoints as Phase 0.
- Diff against the Python state golden, field by field, per ICAO.
- Port `last_position_ts` and per-field write timestamps (needed for §5.12).
- Port `HistoricTracker` semantics onto Room.

**Dependencies** — Phases 0–1.
**Acceptance**
- Every ICAO present in Python's checkpoint is present in Kotlin's, and vice versa.
- Every field matches exactly, except: fields rejected by the Android sanity clamps
  (must be listed, counted, and each individually justified in the test output) and
  fields whose Python value came from FlightAware.
- CPR pair-age, expiry and TCAS edge-count behaviour match on a purpose-built
  fixture that exercises each.

**Performance** — full replay of the 10-minute fixture in < 30 s wall clock.
**Rollback** — harness is test-only; revert is free.

### Phase 3 — USB / RTL-SDR and foreground-service lifecycle

**Scope** — reception is already working; this phase makes it *provably* robust and
adds the missing capture features. **[1-src] From here on, every live test requires
the dongle physically attached to the phone** — the dump1090 network bridge that
previously stood in for OTG hardware no longer exists. Plan hardware time
accordingly; fixtures cover Phases 0–2 without it.
**Modules** — `:app` capture + `PipelineService`.
**Work**
- Port bias-tee (rtl_tcp cmd 0x0E). **Done** — `RtlTcpGain.CMD_SET_BIAS_TEE`,
  live control channel, Settings toggle; unverified against a live dongle/LNA,
  see `PLAN_STATUS.md` §5.
- Expose the demod tuning knobs (`preamble_gap_divisor`, `delta_floor`) through the
  live control path — no restart, same mechanism as gain. **Done.**
- Formalise the back-pressure channel + drop counter (§4.3). **Done** —
  `ReceiverRepository`'s bounded `Channel` + drop counter, see
  `PLAN_STATUS.md` §10.
- Lifecycle matrix test: unplug, replug, screen off, doze, app backgrounded, app
  killed and restarted, permission revoked mid-run, source changed mid-run.

**Dependencies** — Phase 1 module split.
**Acceptance**
- Unplug → `USB LOST` within 3 s; replug → `RUNNING` within 5 s; **uptime and
  session counters are preserved**, matching the Python reconnect-in-source design.
- Antenna-loss shutdown fires at the configured timeout ±5 s and not before.
- No foreground-service crash in any matrix cell (regression guard for the Session 6
  `foregroundServiceType` bug).
- **[1-src]** `ppmCorrection` is the only setting that restarts the pipeline;
  every other setting change is asserted to leave uptime running, both by a
  `ConfigChange` unit test and by an instrumented test that watches uptime across
  each change.
- Cold start with no dongle attached shows `NO SDR` and does **not** start the
  antenna-loss timer; attaching the dongle transitions to `RUNNING` without a
  manual start.

**Performance** — sustained 2 Msps for 60 min with zero overruns; drops = 0 at
nominal traffic.
**Rollback** — feature-flag the tuning knobs; capture changes are additive.

### Phase 3.5 — Core receiver profiling and optimization (backend-only)

**Scope** — optimize the pipeline itself — USB source → demodulator → CRC/parity →
decoder → aircraft state manager → minimal logging — for CPU, allocation/GC churn,
and baseline power draw, with correctness and throughput held fixed. No map, list
UI, Compose layout, Room history, or enrichment is in scope: those layers don't
exist to profile yet (Phase 4/5), and profiling them together with the pipeline
would leave it unclear which layer any regression or win came from. This phase
gives Phase 6 something concrete to compare against once the UI is loaded onto
the same pipeline.
**Modules** — `:core:receiver` (capture/demod/crc/decoder/aircraft) plus a small
headless benchmark harness (a debug build variant or a minimal Activity/Service
entry point with no map/list UI, or a static status readout at most).
**Work**
- **Instrumentation.** Per-stage counters — frames/s, processing latency at each
  stage boundary (SDR read → demod → decode → state update), and CRC outcome
  counts (valid/corrected/recovered/dropped) — exposed as periodic log lines
  (every 10–30 s) and/or an in-memory ring buffer. Identify the hot loops (demod,
  decode dispatch, aircraft-state merge) clearly enough by name/package that a
  profiler attached later doesn't need guidance.
- **Baseline run.** 30–60 minutes headless, against either live RF or a
  deterministic AVR replay fixture (so before/after runs see identical input).
  Capture msg/s over time, CPU usage, and allocation/GC statistics. Write down
  the bottlenecks the data actually shows — no changes yet.
- **Hot-path optimization**, driven by what the baseline found, in these
  categories:
  - *Allocations* — remove per-sample/per-message allocation in the demod loop,
    decoder dispatch/field extraction, and aircraft-state updates; reused
    buffers, primitive arrays over boxed collections where that's a real win.
  - *Data structures* — confirm ICAO/aircraft lookups use appropriate
    primitive-keyed or well-sized maps, and that message-history/signal-level
    deques stay bounded with O(1) operations.
  - *Batching / back-pressure* — confirm SDR reads and demod/decode work in
    sensible chunk sizes rather than excessive small slices, and that the
    `ReceiverRepository` hand-off (Phase 3's drop-counted channel) coalesces or
    drops under load rather than blocking the producer.
  - *Logging* — strip or debug-flag any per-sample/per-message logging in the
    hot loop; the benchmark build logs periodic summaries only.
  - *Threading* — confirm SDR I/O, demod, decode, and state merge aren't
    contending on avoidable locks, critical sections are minimal, and nothing
    in the hot path runs on the main thread even headless.
- **Re-profile** the identical scenario (same device, build, input, duration)
  and compare before vs after: msg/s, CPU, allocation rate, GC frequency. Flag
  any regression immediately rather than averaging it away.
- **Risk and gap write-up** — complexity/reuse-bug risk introduced by any
  pooling, code paths not yet optimized (rare error paths, uncommon message
  types), and assumptions to re-validate once the UI is layered on top in
  Phase 4/5.

**Dependencies** — Phase 3 (the pipeline must be stable end-to-end, including the
back-pressure channel, before its steady-state performance means anything).
**Acceptance** — a profiling report, appended to `PLAN_STATUS.md` as its own
numbered section following the existing session-report pattern (see §8–§10),
containing:
- Overview: scope, device(s), build variant, live vs. AVR data source.
- Baseline snapshot: msg/s, CPU, and GC/allocation timeline, with bottlenecks
  called out.
- Optimization changes: concrete list grouped by category (allocations, data
  structures, logging, threading, batching), each as before/after/observed
  effect.
- Before-vs-after metrics: msg/s, CPU %, allocation/GC frequency, error counts,
  side by side.
- Risks & gaps: what's deliberately left suboptimal or deferred to Phase 6.
- A short "backend baseline" bullet list (msg/s, average CPU, GC behaviour,
  notes) for Phase 6 to diff its own full-app numbers against.
- The parity harnesses (`GoldenFrameParityTests`, `GoldenStateParityTests`) stay
  green throughout — this phase is not permitted to trade correctness for speed.

**Performance targets** (Pixel 6 or equivalent baseline device; refine with real
data, but these are the targets to work toward)
- Throughput: sustained msg/s within **±5%** drift between the first and last
  hour of an hour-long run.
- CPU: average app CPU during a 1 h headless capture **< 1 full core** (e.g.
  < 25% total on a 4-core big.LITTLE device) under realistic traffic.
- Allocations/GC: no large hot-path allocation spikes; no GC pause visibly
  stalls msg/s.
- Battery (optional, informative): the headless backend's power profile
  recorded and clearly lower than the eventual full-app profile — the
  "backend baseline" Phase 6 compares against, not an absolute target.

**Rollback** — instrumentation is additive and debug-only; optimizations land as
ordinary code changes behind the existing parity harnesses, so a regression is
caught by CI rather than by inspection.

### Phase 4 — Receiver dashboard and live aircraft UI

**Scope** — the two screens that make it a console. **This is the primary
deliverable of the whole migration.**
**Modules** — `:core:ui` (new), `:app` ui/live, ui/receiver; repository layer.
**Work**
- Build `ReceiverRepository` (§4.4) with the fixed-rate tick and diffing.
- Design tokens (§5.11) into `:core:ui`; retire ad-hoc colours.
- Live screen: metrics header, sparkline, filters, sort, dense rows, all empty and
  error states, detail sheet/pane.
- Receiver screen: status, config, **tuning sliders + accept-rate window**, pipeline
  counters, rate chart, guidance rules.
- Adaptive scaffold: bar → rail → rail+panes.

**Dependencies** — Phases 1–3.
**Acceptance**
- Every Live and Receiver row in the §5.16 mapping is present or explicitly deferred
  in that table.
- Every state in §5.4's non-nominal table is reachable and screenshot-tested.
- All three wireframe widths render without clipping at 100 % and 200 % font scale.
- Accessibility scan (Compose `a11y` checks + manual TalkBack pass) with zero
  contrast or target-size findings.
- Tuning sliders demonstrably change the accept rate on live hardware, and reset
  the rolling window on change (matching `_reset_win_counters`).

**Performance targets**
- Live list scroll: **zero janky frames** over a 30 s automated scroll at 200
  aircraft (`FrameTimingMetric`, Macrobenchmark).
- Recomposition: changing one aircraft recomposes exactly one row — asserted by
  Compose compiler metrics + a recomposition-count test.
- msg/s with the UI foregrounded and scrolling is within **2 %** of headless.

**Rollback** — new screens live behind the nav graph; the existing `TextListScreen`
stays until Phase 4 acceptance passes, then is deleted in the same commit range.

### Phase 5 — Map, detail, diagnostics, settings, location, watchdog

**Scope** — everything else that was in the CLI, plus the map.
**Modules** — `:app` ui/map, ui/detail, ui/logs, ui/settings; `:data`.
**Work**
- osmdroid map with all marker states, rings, trails, clustering, decimation chip.
- Detail: provenance chips, **`_diagnose()` port**, TCAS section, timeline, raw-frame
  inspector.
- Logs: structured events, category filters, search, unresolved-frame retention,
  **debug-bundle export with privacy toggles**.
- Settings: full grouped + searchable rework, restart-required chips.
- Enrichment: port `enrich/registration.py` (algorithmic ICAO→N-number — offline and
  free, the highest-value enrichment) and `airline_name`; keep adsbdb; **do not**
  port FlightAware.
- Observability: performance + coverage metrics computed on-device — **backend and
  CSV export done ahead of this phase** (`PerformanceMetrics`/`CoverageMetrics` in
  `:core:receiver`, see `docs/PLAN_STATUS.md` §11). What's left here is the UI:
  surfacing them on the Receiver screen and giving the CSV files an in-app export
  entry point (they currently write to app-private storage with no way to reach
  them from the UI).

**Dependencies** — Phase 4 (tokens, repository).
**Acceptance**
- `_diagnose()` output matches Python's string-for-string on a fixture with
  deliberately incomplete aircraft.
- `registration.py` port matches Python on ≥ 1 000 generated ICAOs, both directions.
- Coverage/performance CSV columns are byte-identical in name and order to Python's
  — done, asserted by test. Values match within rounding on the **same replay**
  is not yet done: today's verification checked each formula individually against
  the live Python reference plus one live-hardware run, not a column-diff of both
  implementations over identical recorded input — that remains open if exact
  byte-for-byte CSV parity is wanted.
- Map holds 60 fps while panning at 200 aircraft, and msg/s is unaffected (§5.5).
- Every setting persists across process death; restart-required chips are accurate
  (cross-checked against `ConfigChange`).

**Performance** — map pan/zoom `FrameTimingMetric` P95 < 16 ms at 200 markers;
memory < 250 MB with 500 tracked aircraft and trails on.
**Rollback** — each destination is an independent nav entry; any one can be removed
without affecting the others.

### Phase 6 — Performance, battery, offline, compatibility, long-run reliability

**Scope** — prove it survives real use.
**Modules** — benchmark + instrumented test source sets; no production changes
except fixes found here.
**Work / acceptance**

| Area | Test | Target |
|---|---|---|
| Sustained decode | 8 h continuous live capture | msg/s drift < 5 %, no leak, no ANR, no crash |
| Memory | heap dump at 0 h / 1 h / 8 h | no growth in aircraft, history, event or tile caches after steady state |
| Battery | `Battery Historian`, 1 h screen-off with USB source | quantified mAh/h; documented, with the dominant consumer named |
| Thermal | 1 h at 30 °C ambient | no thermal throttling attributable to the app; if present, demod moves to a lower-priority thread |
| UI isolation | scroll + pan + background + screen-off | msg/s within 2 % of headless in every case |
| Offline | airplane mode, cold start | full function; enrichment degrades silently; map uses cache |
| Compatibility | Pixel 6 (primary), one Android 10 device, one tablet, one foldable | all layouts correct; USB OTG behaviour documented per device |
| Window sizes | compact / medium / expanded, both orientations, split-screen, foldable half-open | no clipping, no lost state, no crash |
| Process death | kill from Settings while running | service restarts, config restored, counters restart cleanly and say so |
| Long-run parity | 8 h capture recorded to AVR, replayed through Python | field-by-field agreement within the Phase 1 allow-list |

**Rollback** — n/a; this phase only produces fixes and a validated-configuration
document.

---

## 7. Decisions and blockers needing your input

**Blocking — needed before Phase 0 finishes**

1. **Reference snapshot location.** Copy `adsb_v9_5` into the Android repo as
   read-only `reference/python/`, or leave it at `D:\SDR\adsb_v9_5` and pin it by
   path + file hashes? Copying makes the parity tests reproducible on any machine
   and makes CI possible; it also duplicates ~12 kLOC into this repo.

2. **The DF11 interrogator-ID bug in your Python receiver.** Android is correct;
   Python discards genuine DF11 replies carrying a non-zero interrogator ID (38 of
   467 frames in the existing test set). Every parity comparison from here on has to
   carry this as an allow-listed exception. Do you want me to fix
   `adsb_v9_5/adsb_receiver/crc/checker.py`? I have not touched it.

**Blocking — needed before Phase 5**

3. **Map library.** I recommend **osmdroid**: offline tile cache, no API key, no
   Play Services, LGPL — the only option that keeps the app fully functional
   offline. Google Maps Compose needs a key, Play Services and connectivity;
   MapLibre gives vector tiles at a much larger footprint. Confirm osmdroid, or name
   the constraint I'm missing.

4. **Aircraft registration/type database.** Three options: (a) algorithmic
   ICAO→N-number only — offline, free, instant, **US aircraft only**; (b) bundle a
   trimmed FAA/OpenSky dataset — tens of MB in the APK, offline, stale between
   releases; (c) on-demand network lookup with a Room cache — small APK, needs
   network, slower first sight. I recommend **(a) + (c)**: algorithmic always,
   network fill-in when enabled. (b) only if you want a fully offline global lookup.

**Non-blocking but worth deciding early**

5. **Distance units.** The CLI shows miles (converting from nautical internally).
   Aviation convention is nautical miles. Default to miles for continuity, with
   nm/km selectable? Or switch the default to nm?

6. **Historic aircraft.** The CLI's "silent 60 s → historic table + daily CSV" is a
   session-scoped concept; Android already has a persistent Room history. Should
   Logs → History show the CLI-style session-scoped list, the full persistent
   history, or both as tabs?

7. **Bias-tee.** Worth porting (Phase 3) only if you own or plan to own a
   bias-tee-powered LNA. It is otherwise a control that can only cause harm.

8. **This repo has no `CLAUDE.md`.** Your global rules require one per project.
   Nothing about the stack, module layout, domain rules or conventions is recorded
   outside `docs/PHASE_PROGRESS.md`. I have not created one — say the word and I
   will, before Phase 0.

**Explicit assumptions made in this plan**

- **Single source, decided.** The USB dongle is the only signal source. Network,
  file, dummy and text replay are removed from the product; file/AVR replay
  survives only in the JVM test harness. This removes the PC bridge that was
  standing in for OTG hardware, so Phase 3 onward needs the dongle attached.
- The Android app remains the deliverable; the Python receiver stays as the
  reference implementation and is never shipped or embedded.
- Reception correctness always outranks UI richness. Any UI feature that measurably
  costs msg/s is cut or throttled, not shipped.
- The existing Session-7 fixes (2.0 Msps, dump1090 demodulator, parity-address CRC
  recovery, live gain) are the correct baseline and are carried forward unchanged.
