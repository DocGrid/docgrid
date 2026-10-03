import glob
import sys
import xml.etree.ElementTree as ET

directory = sys.argv[1] if len(sys.argv) > 1 else "/Users/kangcheolung/cotato/docgrid/backend/build/test-results/test"
totals = [0, 0, 0, 0]
rows = []
for path in sorted(glob.glob(directory + "/*.xml")):
    root = ET.parse(path).getroot()
    counts = [int(root.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
    totals = [a + b for a, b in zip(totals, counts)]
    rows.append((root.get("name").split(".")[-1], counts))
for name, c in rows:
    print(f"{name:70s} tests={c[0]:<4} fail={c[1]:<3} err={c[2]:<3} skipped={c[3]}")
print("합계 tests=%d failures=%d errors=%d skipped=%d" % tuple(totals))
