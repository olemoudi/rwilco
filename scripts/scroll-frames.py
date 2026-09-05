"""One run of scripts/scroll-bench.sh, as the four phases of a frame. See that script."""
import sys
path, label = sys.argv[1], sys.argv[2]
header, rows = None, []
for line in open(path):
    line = line.strip().rstrip(",")
    if line.startswith("Flags,"):
        header = line.split(","); continue
    if header is None or not line or not line[0].isdigit():
        continue
    parts = line.split(",")
    if len(parts) < len(header) - 1:
        continue
    c = dict(zip(header, [int(x) for x in parts[:len(header)]]))
    if c["Flags"] != 0:
        continue
    ui = (c["SyncQueued"] - c["AnimationStart"]) / 1e6
    compose = (c["PerformTraversalsStart"] - c["AnimationStart"]) / 1e6
    layout = (c["DrawStart"] - c["PerformTraversalsStart"]) / 1e6
    record = (c["SyncQueued"] - c["DrawStart"]) / 1e6
    if 0 < ui < 2000:
        rows.append((ui, compose, layout, record))
if not rows:
    print(f"{label}: no usable frames"); raise SystemExit
def pct(p, i):
    v = sorted(r[i] for r in rows)
    return v[min(int(len(v) * p), len(v) - 1)]
print(f"{label}: {len(rows)} frames")
print(f"  UI thread total (compose+measure+layout+record)  median {pct(.5,0):6.1f}  p90 {pct(.9,0):6.1f}  ms")
print(f"    recomposition                                  median {pct(.5,1):6.1f}  p90 {pct(.9,1):6.1f}  ms")
print(f"    measure + layout                               median {pct(.5,2):6.1f}  p90 {pct(.9,2):6.1f}  ms")
print(f"    record display list                            median {pct(.5,3):6.1f}  p90 {pct(.9,3):6.1f}  ms")
