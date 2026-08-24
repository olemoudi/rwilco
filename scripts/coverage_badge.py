#!/usr/bin/env python3
"""Generate a shields-style coverage badge from the aggregated JaCoCo CSV.

Self-contained (stdlib only) so CI needs no third-party action. Reads instruction
coverage across all rows and writes a flat SVG badge.
"""
import csv
import sys
from pathlib import Path

CSV = Path("build/reports/jacoco/jacocoAggregatedReport/jacocoAggregatedReport.csv")
OUT = Path(".github/badges/coverage.svg")


def coverage_percent() -> float:
    missed = covered = 0
    with CSV.open() as f:
        for row in csv.DictReader(f):
            missed += int(row["INSTRUCTION_MISSED"])
            covered += int(row["INSTRUCTION_COVERED"])
    total = missed + covered
    return 100.0 * covered / total if total else 0.0


def color(pct: float) -> str:
    for threshold, c in ((90, "#4c1"), (80, "#97ca00"), (70, "#dfb317"), (60, "#fe7d37")):
        if pct >= threshold:
            return c
    return "#e05d44"


def text_width(s: str) -> int:
    # Rough average glyph width for Verdana 11px; good enough for a badge.
    return int(len(s) * 6.5) + 10


def badge(pct: float) -> str:
    label, value = "coverage", f"{pct:.0f}%"
    lw, rw = text_width(label), text_width(value)
    w = lw + rw
    lx, rx = lw * 10 // 2, (lw + rw // 2) * 10
    ltl, rtl = (lw - 10) * 10, (rw - 10) * 10
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="20" role="img" aria-label="{label}: {value}">
<title>{label}: {value}</title>
<linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>
<clipPath id="r"><rect width="{w}" height="20" rx="3" fill="#fff"/></clipPath>
<g clip-path="url(#r)">
<rect width="{lw}" height="20" fill="#555"/>
<rect x="{lw}" width="{rw}" height="20" fill="{color(pct)}"/>
<rect width="{w}" height="20" fill="url(#s)"/>
</g>
<g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="110">
<text x="{lx}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)" textLength="{ltl}">{label}</text>
<text x="{lx}" y="140" transform="scale(.1)" textLength="{ltl}">{label}</text>
<text x="{rx}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)" textLength="{rtl}">{value}</text>
<text x="{rx}" y="140" transform="scale(.1)" textLength="{rtl}">{value}</text>
</g>
</svg>
"""


def main() -> int:
    if not CSV.exists():
        print(f"coverage CSV not found at {CSV}; run :jacocoAggregatedReport first", file=sys.stderr)
        return 1
    pct = coverage_percent()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(badge(pct))
    print(f"coverage {pct:.1f}% -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
