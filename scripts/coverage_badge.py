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


# What these glyphs draw at 11px, in pixels.
#
# Calibrated against shields.io's own geometry for the same two strings, which is the only
# reference that matters here: it puts "coverage" at 51.0px and "84%" at 25.0px. One average for
# every character is what this used to do, and it is right for a word — letters really do come
# out near 6.4 — but a digit is wider than a letter and "%" is nearly twice one, so "84%" was
# measured at 19.0px against the 25.0px it draws.
#
# Six pixels short is not a cosmetic error, because of textLength below: the browser honours the
# promise by taking the missing width out of the spacing between the glyphs, and three glyphs
# pulled six pixels together touch. The per cent sign is the widest of them and the one that
# visibly collided — reported as "el % sale mal dibujado".
GLYPHS = {"%": 11.0, ".": 3.9, " ": 3.5, **{d: 7.0 for d in "0123456789"}}
AVERAGE_GLYPH = 6.4

# Space either side of the text inside its half of the badge.
PADDING = 10


def text_width(s: str) -> float:
    """How wide [s] actually draws, in badge pixels."""
    return sum(GLYPHS.get(ch, AVERAGE_GLYPH) for ch in s)


def badge(pct: float) -> str:
    """A flat badge, sized around its text rather than the other way round.

    `textLength` is kept, as shields.io keeps it: it is what stops a reader whose browser
    substitutes a wider font from having the text spill out of its colour. It is only safe while
    the number is honest, though — an *under*-measured string is packed until its glyphs touch,
    which is the bug this replaced. Measure first, then draw the box around the measurement.
    """
    label, value = "coverage", f"{pct:.0f}%"
    ltl, rtl = text_width(label), text_width(value)
    lw, rw = round(ltl + PADDING), round(rtl + PADDING)
    w = lw + rw
    # Centres of the two halves, in the 10x space the text group is scaled down from. Halved as
    # a float: integer division put the value half a pixel off its own box.
    lx, rx = round(lw / 2 * 10), round((lw + rw / 2) * 10)
    ltl, rtl = round(ltl * 10), round(rtl * 10)
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
