#!/usr/bin/env python3
"""
Generate core/receiver/.../enrich/Airlines.kt from the Python receiver's airline table.

Transcribing 155 mappings by hand is a guaranteed source of silent drift, so the
Kotlin table is generated from `enrich/lookup.py:_AIRLINE_MAP` instead. Re-run
after changing the Python table.

    python tools/gen_airlines.py --python-root D:/SDR/adsb_v9_5
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

OUT = Path("core/receiver/src/main/kotlin/com/laviavi/adsbandroid/enrich/Airlines.kt")

HEADER = '''package com.laviavi.adsbandroid.enrich

/**
 * Operator name from the 3-letter ICAO airline prefix of a callsign.
 *
 * Generated from the Python receiver's `enrich/lookup.py:_AIRLINE_MAP` by
 * `tools/gen_airlines.py` — edit the table there and regenerate, do not hand-edit
 * this file. Offline and free: the prefix is already inside the decoded callsign,
 * so no lookup of any kind is required.
 *
 * A callsign only carries an operator when it is a flight-number callsign
 * ("UAL2184"). General-aviation aircraft transmit their registration as the
 * callsign ("N38901"), which has no operator and correctly returns null.
 */
object Airlines {

    /** Operator name for a flight-number callsign, or null if the prefix is unknown. */
    fun fromCallsign(callsign: String?): String? {
        val cs = callsign?.trim()?.uppercase() ?: return null
        if (cs.length < 3) return null
        return MAP[cs.substring(0, 3)]
    }

    /** True when the callsign is the aircraft's own registration, not a flight number. */
    fun isRegistrationCallsign(callsign: String?): Boolean {
        val cs = callsign?.trim()?.uppercase() ?: return false
        return cs.length >= 2 && cs[0] == 'N' && cs[1].isDigit()
    }

    val size: Int get() = MAP.size

    private val MAP: Map<String, String> = mapOf(
'''

FOOTER = '''    )
}
'''


def kotlin_string(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--python-root", required=True)
    args = ap.parse_args(argv)

    sys.path.insert(0, str(Path(args.python_root).resolve()))
    from adsb_receiver.enrich.lookup import _AIRLINE_MAP as table

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(HEADER)
        for prefix in sorted(table):
            fh.write(f'        "{prefix}" to "{kotlin_string(table[prefix])}",\n')
        fh.write(FOOTER)

    print(f"wrote {OUT} — {len(table)} airlines")
    return 0


if __name__ == "__main__":
    sys.exit(main())
