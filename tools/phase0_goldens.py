#!/usr/bin/env python3
"""
Phase 0 — capture the Python receiver's behaviour as golden reference files.

Reads fixtures, drives the *Python* pipeline, writes TSV that the Kotlin replay
harness diffs against field by field.  The Python project is imported read-only
and is never modified.

Two outputs per fixture:

  <name>.frames.tsv   one row per frame leaving CRC+decode
  <name>.state.tsv    the full aircraft table, every CHECKPOINT_SEC of virtual time

Determinism
-----------
Everything runs on a virtual clock so a second run is byte-identical and so a
10-minute recording behaves like 10 minutes rather than like the 2 seconds it
takes to replay.  Two things must be controlled for that to hold:

  * frame timestamps    — injected, never time.time()
  * the ICAO cache TTL  — icao_cache.py calls time.time() directly, so its
                          module-level reference is redirected at the virtual
                          clock for the duration of the run.  Without this the
                          60 s TTL never expires during a fast replay and
                          recovered-frame counts come out too high.
  * first_seen          — AircraftState declares
                          `first_seen: float = field(default_factory=time.time)`,
                          which binds the real clock at object construction and
                          cannot be redirected after import.  The first message
                          timestamp per ICAO is tracked here instead; in a live
                          run the two are the same instant, so this is the
                          faithful value and the one Kotlin must reproduce.

Usage
-----
    python tools/phase0_goldens.py --python-root D:/SDR/adsb_v9_5
    python tools/phase0_goldens.py --python-root ... --only modes1
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator, List, Optional

CHECKPOINT_SEC = 10.0
AVR_FRAME_INTERVAL_SEC = 0.1   # matches TextFileSource's real-time pacing estimate
IQ_READ_SIZE = 262_144         # CaptureConfig.read_size
SAMPLE_RATE = 2_000_000


# ---------------------------------------------------------------------------
# Virtual clock
# ---------------------------------------------------------------------------

class VirtualClock:
    """Monotonic clock driven by frame timestamps, not by wall time."""

    def __init__(self) -> None:
        self.now = 0.0

    def advance_to(self, t: float) -> None:
        if t > self.now:
            self.now = t

    def __call__(self) -> float:
        return self.now


# ---------------------------------------------------------------------------
# Column definitions — order is the contract with the Kotlin side
# ---------------------------------------------------------------------------

FRAME_COLUMNS = [
    "seq", "ts_ms", "hex", "len", "df",
    "crc_status", "crc_remainder", "corrected_bit", "recovered_icao",
    "icao", "callsign", "squawk", "tc", "message_type", "emitter_category",
    "latitude", "longitude", "altitude_baro_ft", "altitude_gnss_ft", "on_ground",
    "cpr_lat", "cpr_lon", "cpr_format",
    "ground_speed_kts", "track_deg", "heading_deg", "airspeed_kts",
    "vertical_rate_fpm", "speed_type",
    "capability", "ii_code", "version", "nic_supplement", "nac_p", "nac_v", "sil",
    "tcas_sl", "tcas_ra_active", "tcas_ra_text", "tcas_ra_complement",
    "tcas_ra_terminated", "tcas_target_icao",
]

STATE_COLUMNS = [
    "checkpoint_s", "icao", "callsign", "squawk", "emitter_category",
    "latitude", "longitude", "altitude_baro_ft", "altitude_gnss_ft", "on_ground",
    "ground_speed_kts", "track_deg", "heading_deg", "airspeed_kts",
    "vertical_rate_fpm", "speed_type",
    "version", "nac_p", "nac_v", "sil",
    "tcas_sl", "tcas_ra_active", "tcas_ra_text", "tcas_ra_complement",
    "tcas_ra_terminated", "tcas_target_icao", "tcas_event_count",
    "interrogator_ids", "msg_count", "valid_count", "bad_crc_count",
    "corrected_count", "first_seen_ms", "last_seen_ms", "last_position_ms",
]


def _cell(v: object) -> str:
    """Render one value. Floats are fixed-precision so the diff is exact."""
    if v is None:
        return ""
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, float):
        return f"{v:.6f}"
    if isinstance(v, str):
        return v.replace("\t", " ").replace("\n", " ").strip()
    return str(v)


def _enum_value(v: object) -> object:
    return getattr(v, "value", v)


# ---------------------------------------------------------------------------
# Fixture readers — each yields (frame_bytes | iq_buffer, virtual_timestamp)
# ---------------------------------------------------------------------------

@dataclass
class Fixture:
    name: str
    path: Path
    kind: str          # "iq" | "avr"


def read_avr_frames(path: Path) -> Iterator[tuple[bytes, float]]:
    """Yield (frame_bytes, ts) from a *HEX; recording, evenly spaced."""
    i = 0
    with open(path, "r", encoding="ascii", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not (line.startswith("*") and line.endswith(";")):
                continue
            try:
                frame = bytes.fromhex(line[1:-1])
            except ValueError:
                continue
            if len(frame) not in (7, 14):
                continue
            yield frame, i * AVR_FRAME_INTERVAL_SEC
            i += 1


def read_iq_buffers(path: Path) -> Iterator[tuple[bytes, float]]:
    """Yield (iq_bytes, buffer_start_ts) at the real-time rate of the capture."""
    seconds_per_buffer = IQ_READ_SIZE / (SAMPLE_RATE * 2)
    i = 0
    with open(path, "rb") as fh:
        while True:
            buf = fh.read(IQ_READ_SIZE)
            if not buf:
                return
            yield buf, i * seconds_per_buffer
            i += 1


# ---------------------------------------------------------------------------
# Replay
# ---------------------------------------------------------------------------

def replay(fixture: Fixture, py, clock: VirtualClock) -> tuple[list, list]:
    """
    Drive the Python pipeline over one fixture.

    Components are driven directly rather than through ADSBReceiver, which
    spawns threads and reads wall-clock time — neither is reproducible.
    The stage order is identical to core.py:_process_raw.
    """
    icao_cache = py.ICAOCache()
    crc = py.CRCChecker(check_enabled=True, correct_single_bit=True,
                        icao_cache=icao_cache)
    decoder = py.MessageDecoder()
    manager = py.AircraftManager(config=py.AircraftConfig(
        expiry_seconds=60.0, max_history_per_aircraft=50))
    demod = py.Demodulator(py.DemodConfig(), crc)

    frame_rows: List[list] = []
    state_rows: List[list] = []
    first_msg_ts: dict = {}
    seq = 0
    next_checkpoint = CHECKPOINT_SEC

    def emit_checkpoint(at: float) -> None:
        for ac in sorted(manager.all(), key=lambda a: a.icao):
            state_rows.append([
                int(at), ac.icao, ac.callsign, ac.squawk, ac.emitter_category,
                ac.latitude, ac.longitude, ac.altitude_baro_ft,
                ac.altitude_gnss_ft, ac.on_ground,
                ac.ground_speed_kts, ac.track_deg, ac.heading_deg,
                ac.airspeed_kts, ac.vertical_rate_fpm, ac.speed_type,
                ac.version, ac.nac_p, ac.nac_v, ac.sil,
                ac.tcas_sl, ac.tcas_ra_active, ac.tcas_ra_text,
                ac.tcas_ra_complement, ac.tcas_ra_terminated,
                ac.tcas_target_icao, ac.tcas_event_count,
                ac.interrogator_ids_str,
                ac.msg_count, ac.valid_count, ac.bad_crc_count,
                ac.corrected_count,
                int(first_msg_ts.get(ac.icao, 0.0) * 1000),
                int(ac.last_seen * 1000),
                int(ac.last_position_ts * 1000) if ac.last_position_ts else None,
            ])

    def handle_raw(raw) -> None:
        nonlocal seq, next_checkpoint

        clock.advance_to(raw.timestamp)

        # Expiry runs on a timer thread in core.py; here it is driven off the
        # virtual clock at every checkpoint boundary, which is equivalent for a
        # 60 s expiry sampled every 10 s.
        while raw.timestamp >= next_checkpoint:
            manager.purge_expired()
            emit_checkpoint(next_checkpoint)
            next_checkpoint += CHECKPOINT_SEC

        status = raw.crc_status
        # core.py drops bad CRC (emit_bad_crc defaults False) and drops
        # unresolved parity-address frames, whose ICAO would be garbage.
        # Both are still recorded here so the Kotlin side must agree on the
        # classification, not merely on what survived it.
        decoded = None
        if status not in ("bad", "parity_addr"):
            try:
                decoded = decoder.decode(raw)
            except Exception:
                decoded = None
            if decoded is not None:
                # MessageDecoder.decode() builds DecodedMessage without passing
                # the frame's timestamp through, so `timestamp` falls back to
                # its default_factory=time.time — the wall clock at *decode*
                # time rather than at reception.  AircraftManager._merge uses
                # that value for last_seen, CPR pair ageing and
                # last_position_ts, so on any replay the CPR 10 s pair window is
                # measured against the wrong clock entirely.  Live, the two
                # instants differ by microseconds and it never shows.
                # Reception time is the intended value; use it.
                decoded.timestamp = raw.timestamp
            if decoded is not None and decoded.icao:
                first_msg_ts.setdefault(decoded.icao.upper(), raw.timestamp)
                manager.update(decoded)

        d = decoded
        frame_rows.append([
            seq, int(round(raw.timestamp * 1000)), raw.hex,
            len(raw.raw_bytes), raw.df,
            raw.crc_status, raw.crc_remainder, raw.corrected_bit,
            f"{raw.recovered_icao:06X}" if raw.recovered_icao else None,
            d.icao if d else None,
            (d.callsign.strip() if d and d.callsign else None),
            d.squawk if d else None,
            d.tc if d else None,
            _enum_value(d.message_type) if d else None,
            d.emitter_category if d else None,
            d.latitude if d else None, d.longitude if d else None,
            d.altitude_baro_ft if d else None, d.altitude_gnss_ft if d else None,
            d.on_ground if d else None,
            d.cpr_lat if d else None, d.cpr_lon if d else None,
            _enum_value(d.cpr_format) if d and d.cpr_format is not None else None,
            d.ground_speed_kts if d else None, d.track_deg if d else None,
            d.heading_deg if d else None, d.airspeed_kts if d else None,
            d.vertical_rate_fpm if d else None, d.speed_type if d else None,
            d.capability if d else None, d.ii_code if d else None,
            d.version if d else None, d.nic_supplement if d else None,
            d.nac_p if d else None, d.nac_v if d else None, d.sil if d else None,
            d.tcas_sl if d else None, d.tcas_ra_active if d else None,
            d.tcas_ra_text if d else None, d.tcas_ra_complement if d else None,
            d.tcas_ra_terminated if d else None, d.tcas_target_icao if d else None,
        ])
        seq += 1

    if fixture.kind == "avr":
        for frame, ts in read_avr_frames(fixture.path):
            df = (frame[0] >> 3) & 0x1F
            result = crc.check(frame, df)
            effective = result.corrected_data or frame
            handle_raw(py.RawMessage(
                raw_bytes=effective, timestamp=ts, df=df, signal_level=1.0,
                crc_status=result.status.value, crc_remainder=result.remainder,
                corrected_bit=result.corrected_bit,
                recovered_icao=result.recovered_icao,
            ))
    else:
        for buf, ts in read_iq_buffers(fixture.path):
            for raw in demod.process_buffer(buf, timestamp=ts):
                handle_raw(raw)

    emit_checkpoint(next_checkpoint)
    return frame_rows, state_rows


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def write_tsv(path: Path, columns: List[str], rows: List[list]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\t".join(columns) + "\n")
        for row in rows:
            fh.write("\t".join(_cell(v) for v in row) + "\n")
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--python-root", required=True,
                    help="Path to the adsb_v9_5 project (read-only)")
    ap.add_argument("--out", default="core/receiver/src/test/resources/fixtures/golden",
                    help="Output directory for TSV goldens")
    ap.add_argument("--only", default=None, help="Run a single fixture by name")
    args = ap.parse_args(argv)

    root = Path(args.python_root).resolve()
    if not (root / "adsb_receiver").is_dir():
        print(f"ERROR: no adsb_receiver package under {root}", file=sys.stderr)
        return 1
    sys.path.insert(0, str(root))

    # Import the reference implementation.
    import adsb_receiver.crc.icao_cache as icao_cache_mod
    from adsb_receiver.aircraft.manager import AircraftManager
    from adsb_receiver.config import AircraftConfig, DemodConfig
    from adsb_receiver.crc.checker import CRCChecker
    from adsb_receiver.crc.icao_cache import ICAOCache
    from adsb_receiver.decoder.adsb_decoder import MessageDecoder
    from adsb_receiver.decoder.models import RawMessage
    from adsb_receiver.demod.demodulator import Demodulator
    from adsb_receiver.version import __version__

    class PyRefs:
        pass
    py = PyRefs()
    py.AircraftManager, py.AircraftConfig = AircraftManager, AircraftConfig
    py.DemodConfig, py.CRCChecker, py.ICAOCache = DemodConfig, CRCChecker, ICAOCache
    py.MessageDecoder, py.RawMessage, py.Demodulator = MessageDecoder, RawMessage, Demodulator

    clock = VirtualClock()
    real_time = icao_cache_mod.time.time
    icao_cache_mod.time.time = clock          # 60 s TTL now expires on virtual time

    fixtures = [
        Fixture("modes1_iq", root / "tests/fixtures/modes1.bin", "iq"),
        Fixture("avr_20260621", root / "recordings/adsb_20260621.txt", "avr"),
        Fixture("avr_20260622", root / "recordings/adsb_20260622.txt", "avr"),
    ]

    out_dir = Path(args.out)
    manifest = {
        "python_version": __version__,
        "python_root": str(root),
        "checkpoint_sec": CHECKPOINT_SEC,
        "avr_frame_interval_sec": AVR_FRAME_INTERVAL_SEC,
        "fixtures": {},
    }

    try:
        for fx in fixtures:
            if args.only and args.only not in fx.name:
                continue
            if not fx.path.exists():
                print(f"SKIP {fx.name}: {fx.path} not found")
                continue

            clock.now = 0.0
            frames, states = replay(fx, py, clock)

            fh = write_tsv(out_dir / f"{fx.name}.frames.tsv", FRAME_COLUMNS, frames)
            sh = write_tsv(out_dir / f"{fx.name}.state.tsv", STATE_COLUMNS, states)

            by_status: dict = {}
            for r in frames:
                by_status[r[5]] = by_status.get(r[5], 0) + 1
            aircraft = len({r[1] for r in states})

            manifest["fixtures"][fx.name] = {
                "source": fx.path.name,
                "kind": fx.kind,
                "source_sha256": hashlib.sha256(fx.path.read_bytes()).hexdigest(),
                "frames": len(frames),
                "crc_status_counts": by_status,
                "state_rows": len(states),
                "distinct_aircraft": aircraft,
                "frames_tsv_sha256_16": fh,
                "state_tsv_sha256_16": sh,
            }
            print(f"{fx.name:14s} {len(frames):7,} frames  "
                  f"{aircraft:4d} aircraft  {by_status}")
    finally:
        icao_cache_mod.time.time = real_time

    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"\nWrote {out_dir}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
