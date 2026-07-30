# Correction Plan — Python↔Android Formula Audit

**Date:** 2026-07-26
**Reference of truth:** the Python receiver at `D:\SDR\adsb_v9_5`
**Scope:** ported subsystems only — demod, CRC, decoder, aircraft state/merge,
enrichment, location, observability, stats, config defaults. Deliberately
unported parts (FlightAware scraper, FastAPI web console, curses UI,
network/file/dummy sources) are out of scope.
**Depth:** numeric formulas plus the surrounding semantics — thresholds,
defaults, rounding modes, counting policy, merge rules, ordering, null handling.

**Method:** every finding below was verified by executing the Python reference
directly (or by an existing parity harness), not by reading alone. Numbers in
the Error column are measured, not estimated.

**Status:** findings only. No fixes analysed or applied — that was explicitly
out of scope for this pass.

---

## 1. Verified identical — no discrepancy

| Area | Verification |
|---|---|
| Magnitude LUT (`sqrt(i²+q²)*360`) | all 16,641 entries identical (`np.round` vs `Math.round` ties proven unreachable) |
| IQ→magnitude, odd-byte trim, 0..128 clamp | identical |
| Preamble inequality chain + gap divisor | identical |
| Bit slicer, `delta<256` carry, delta-floor gate | identical |
| Signal level `(m0+m2+m7+m9)/4/65535` | identical |
| CRC **validity** (zero/non-zero) | 0 disagreements in 50,000 random frames |
| Parity-address candidate ICAO | Kotlin `computeCrc` **is** `pi XOR CRC(data)` — 0/40,000 mismatches |
| Single-bit correction | 88 syndromes unique, none zero → Python "first match" ≡ Kotlin map |
| ICAO cache hash / TTL / contains | 0 hash mismatches |
| 13-bit + 12-bit Gillham altitude | identical incl. `n100` remap and `n500%2` flip |
| 12-bit AC mask `0x1FFF` vs `0x0FFF` | equivalent — value can never exceed `0xFFF` |
| Squawk `_decode_identity` | identical |
| CPR NL table | 0 mismatches in 300,000 random latitudes |
| CPR global core math (`j`, `m`, `ni`, dlon) | identical |
| Haversine (R=3440.065) and bearing | identical (Python's `atan2(x,y)` ≡ Kotlin's `atan2(y,x)` — naming only) |
| ICAO→N-number registration | 915,399-address SHA-256 digest parity test |
| `airline_name` 3-char prefix | identical |
| DF16 SL / altitude / BDS 3,0 RA / `_resolve_ap_icao` | identical |
| Coverage + performance metrics | verified when ported (see `PLAN_STATUS.md` §11) |

---

## 2. Discrepancies — wrong formula

| # | Python (truth) | Android | Error |
|---|---|---|---|
| 1 | **DF11 II code:** `addr = pi ^ crc24(data[:-3])`; `ii = (addr >> 20) & 0x0F` | `ii = bytes[6] and 0x0F` | **No XOR, no CRC, wrong bits.** Reads the raw low nibble of the last PI byte instead of the recovered address's top nibble. **18,764/20,000 values differ.** `iiCode` is essentially a random number. |
| 2 | **TC20-22 GNSS altitude:** `alt_raw * 25 - 1000`, stored in `altitude_gnss_ft` | routed through `decodeAc12Field` (Gillham/Q-bit), stored in `altitudeFt` | **Wrong formula and wrong field.** Differs on **all 4,095** raw values; most return `null` where Python returns feet. GNSS altitude also silently overwrites barometric altitude — Python keeps them separate. |
| 3 | **TC19 airspeed:** `airspeed_kts`, `speed_type = ias/tas` | written to `fields.groundSpeedKt` | Airspeed is reported as ground speed. `speed_type` does not exist on the Android side. |
| 4 | **TC19 heading:** `heading_deg = hdg_raw/1024*360` (magnetic heading) | written to `fields.trackDeg` | Magnetic heading reported as true track. Ratio itself is correct (`45/128 == 360/1024`). |
| 5 | **TC31 NACp:** `nac_p = me[3] & 0x0F` | `nacP = bytes[9] and 0x0F` (= `me[5]`) | **Wrong byte** — reads NACp out of the version byte. |
| 6 | **TC31 SIL:** `sil = (me[4] >> 1) & 0x03` | `sil = (bytes[10] and 0x30) ushr 4` (= `me[6]`) | **Wrong byte and wrong bits.** |
| 7 | **TC31 NACv:** `nac_v = (me[4] >> 3) & 0x07` | not decoded | Field missing entirely. |

---

## 3. Discrepancies — missing or mis-scoped logic

| # | Python (truth) | Android | Error |
|---|---|---|---|
| 8 | **DF4:** `fs = (data[0]>>2)&7`; `on_ground = fs in (1,3,5)` | not decoded | Flight-status ground flag never read. An existing test *asserts* this absence, locking in the divergence. |
| 9 | **DF16:** `msg.on_ground = False` | not set | DF16 never clears a stale ground flag. |
| 10 | **Airborne pos (TC9-18/20-22):** `on_ground = False`, merged via `if msg.on_ground is not None` | merge only applies `onGround` when `typecode in 5..8` | Once set true by a surface message, `onGround` can never be cleared — a departing aircraft stays "on ground". |
| 11 | **TC1-4:** `emitter_category = (tc-1)*8 + (me[0]&7)` | not decoded | `AircraftState.category` exists but nothing ever writes it. |
| 12 | **CPR:** `is_odd_latest = odd_ts > even_ts` (strict) | `useOdd = odd.ts >= even.ts` | On a tie Python picks **even**, Android picks **odd** → different lat/lon. Ties are rare in Python (per-frame timestamps) but **common** in Android (one `nowMs` per buffer, see #19). |
| 13 | **CPR:** `round(lat,6)`, `round(lon,6)` | no rounding | Full float precision retained; values differ from the reference in the 7th+ decimal. |
| 14 | **CPR state** lives in `AircraftState`, deleted on expiry | `cprEven`/`cprOdd` maps in `MessageDecoder` | Never purged. Unbounded growth, and a re-appearing ICAO can pair against a frame from a previous session. |
| 15 | **II codes:** `interrogator_ids` is a **set** (rendered `"3;7"`) | scalar `iiCode`, last-wins | Only one interrogator ever retained. |
| 16 | **Signal:** `signal_levels` deque(20) → `avg_signal` | scalar `signalDbfs`, latest only | No averaging — the UI's Sig % is a single frame, not a 20-frame mean. *(Listed "partial" in the migration plan.)* |
| 17 | **History:** `message_history` deque(50) per aircraft | absent | Migration plan records this as "Port ✅ done" — **it is not implemented.** Blocks the detail-view timeline. |
| 18 | Per-aircraft `valid_count` / `corrected_count` / `bad_crc_count`; `last_position_ts` | only `messageCount` + `lastCrcResult` | Per-aircraft reception breakdown unavailable. |
| 19 | **Frame timestamp:** `timestamp + j/2_000_000` (sample-accurate within buffer) | `RawFrame.clockCount` never set; one `nowMs` per buffer | All frames in a 65.5 ms buffer share one timestamp. Drives #12; harmless for CPR ageing, fatal for any future MLAT. |

---

## 4. Discrepancies — counters and rates

| # | Python (truth) | Android | Error |
|---|---|---|---|
| 20 | `corrected` → increments **both** `valid` and `corrected` | `CORRECTED` → increments `correctedMessages` only | `validMessages` excludes corrected frames; Python's `valid` includes them. (Compensated inside `PerformanceMetrics`, but the UI counter's meaning differs.) |
| 21 | `messages_per_second = total / elapsed_since_start` (session average) | `(total - lastTotal)` (instantaneous 1 s delta) | Different metric — a session average vs a live rate. |
| 22 | Separate `parity_addr`, `undecodable`, `df_counts` counters | `parity_addr` folded into `invalidMessages`; other two absent | Three-way CRC split collapsed. *(Noted in migration plan §2.2.)* |

---

## 5. Android-only behaviour with no Python counterpart

| # | Android | Note |
|---|---|---|
| 23 | **TC29 decode** (selected alt/heading, autopilot, baro setting, emergency squawk) | Python is explicitly `elif tc == 29: pass`. Not listed in the documented-divergence table. Includes synthesising squawk `7700/7600/7500` from the emergency code — Python never does this. |
| 24 | **Surface movement → ground speed / track** (TC5-8) | Python decodes only CPR + `on_ground` for surface. |
| 25 | **Surface track line has an operator-precedence bug:** `x and 0x007F * 45` parses as `x and (0x7F*45)` = `x and 5715`, not `(x and 0x7F) * 45` | Verified: intended 168, actual 33. Android-only code, so no Python divergence — but it is wrong on its own terms. |
| 26 | `isRegistrationCallsign` (GA callsign = registration, `DECODED` beats `ALGORITHMIC`) | Python has no such rule. |
| 27 | `confirmedIcaoCache` in `MessageDecoder` — `LinkedHashMap`, no eviction | Grows unbounded. Its `else` branch also appears unreachable (all non-pure-CRC DFs return early or carry `recoveredIcao`). |
| 28 | Callsign `.trimEnd('@',' ')` + empty→null | Python `.strip()` — whitespace only, **both** ends, keeps `@`, returns `""`. Diverges only on `@`-padded or leading-space callsigns (absent from the 44-min capture). |
| 29 | `frameIcao` skips ICAO 0; Python calls `add(0)` | Python evicts a real ICAO from slot `hash(0)=0`; Android doesn't. Python's `contains(0)` is always false anyway. |

---

## 6. Known / deliberate divergences — re-verified, still accurate

| Item | Status |
|---|---|
| DF11 IID gate uses `crc24(whole_frame)<80` (Python) vs syndrome `<80` (Android) | **Confirmed still divergent** as documented — 1 gate disagreement per 200,000 random DF11 frames |
| TC19 subtype: Python `(me[0]>>1)&7` (bug) vs Android `me[0]&7` (correct) | Confirmed — Android correct, Python populates ground speed 0× in the capture |
| Speed >700 kt / altitude −1500…72000 clamps | Confirmed present. **Placement differs:** Python drops speed *and* track together in the decoder; Android clamps only speed in the manager and **keeps the track** |
| DF20/21 Comm-B callsign + MB decode | Confirmed Android-only, as documented |
| CPR relative (observer-based) decode | Confirmed Android-only, as documented |
| FA columns always `0` | Confirmed |
| Gain default: Python `40.0` dB fixed vs Android auto-gain | Confirmed — deliberate Android choice, recorded in `PLAN_STATUS.md` |
| NL boundary constants truncated to 8 dp in Kotlin | Differs from Python only *exactly on* 29 of 58 boundary values; 0 mismatches across 300,000 random latitudes — theoretical only |

---

## 7. Highest-impact items

Backend correctness with user-visible effect:

- **#1** DF11 II code — wrong formula
- **#2** TC20-22 GNSS altitude — wrong formula *and* overwrites barometric altitude
- **#5 / #6** TC31 NACp and SIL read from the wrong bytes
- **#10** sticky ground flag — never cleared once set
- **#3 / #4** TC19 airspeed and heading written into the wrong fields

## 8. Documentation defects found during the audit

Independent of the code, two project docs are currently wrong:

- `PLAN_STATUS.md` claims per-aircraft message history is ported (**#17**) — it is not implemented.
- `ANDROID_MIGRATION_PLAN.md`'s deliberate-divergence table does not list the TC29 decode (**#23**).
