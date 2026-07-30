# adsb-android

Android ADS-B receiver. USB RTL-SDR dongle over OTG is the **only** signal source.
Behavioural reference: the Python receiver at `D:\SDR\adsb_v9_5` (read-only).

## Reporting rule

Work silently. After coding, give a **brief** summary — three headings, terse
bullets, no prose paragraphs:

- **Done** — what changed
- **Next** — what to do now
- **Incomplete** — what is unfinished, unverified, or blocked

No narration between tool calls. No restating the request. No explaining code that
speaks for itself. Deviations from instructions must be stated; everything routine
stays unmentioned.

## Keep the status document current

`docs/PLAN_STATUS.md` is a living document, not a dated audit. **Update it at the
end of every prompt that changes code**, before the summary — same turn, not
"next time".

- Rewrite the affected sections so the document describes the code as it is now.
  Never append a correction section that contradicts what is above it.
- Move completed items out of the remaining path; add anything newly discovered to
  the defects table.
- Verify by reading the code, not by recalling what happened this session.

## Layout

| Module | Contains |
|---|---|
| `:core:receiver` | Pure Kotlin: demod, crc, decoder, aircraft, enrich, location, capture logic. JVM-tested. **All shared code lives here only.** (renamed from `:core-test` 2026-07-26 — the old name called it a test module when everything in `src/main/kotlin` ships) |
| `:app` | Android only: service, UI, Room, USB, DI. Depends on `:core:receiver`. |

Never copy a file between modules — that duplication caused two green tests to
certify the sample-rate bug that broke reception (Session 7/8).

## Key constraints

- **2.0 Msps exactly.** `RtlSdrDefaults.SAMPLE_RATE_HZ` must equal
  `Demodulator.REQUIRED_SAMPLE_RATE_HZ`. At 2.4 Msps nothing decodes.
- `ppmCorrection` and switching auto/manual gain mode (`autoGain`) restart the
  pipeline. Picking a different level within the current mode, and everything
  else, applies live over the rtl_tcp control channel.
- Manual gain defaults to `GAIN_UNSET`, not 0 — 0 is the R82xx minimum.
- Enrichment: offline (ICAO→N-number, airline prefix) always runs; network (adsbdb
  route) is opt-in and must never block decoding.

## Docs

| File | Purpose |
|---|---|
| `docs/PLAN_STATUS.md` | **Start here** — code-vs-plan audit, what's done, ordered path to completion |
| `docs/ANDROID_MIGRATION_PLAN.md` | Migration plan, UI/UX spec, phase roadmap, open decisions |
| `docs/PHASE_PROGRESS.md` | Session-by-session changelog |

## Tools

| Script | Purpose |
|---|---|
| `tools/phase0_goldens.py` | Regenerate Python golden fixtures (parity reference) |
| `tools/gen_airlines.py` | Regenerate `Airlines.kt` from the Python table |
| `tools/gen_registration_parity.py` | Regenerate the ICAO→N-number parity digest |

Generated Kotlin is never hand-edited — change the Python source and regenerate.

## Testing

```bash
./gradlew.bat :core:receiver:test :app:testDebugUnitTest :app:assembleDebug
```

Parity against Python is the standard of correctness, not visual inspection.
Divergences from the reference must be deliberate, documented, and asserted by an
allow-list test — never absorbed by a tolerance.
