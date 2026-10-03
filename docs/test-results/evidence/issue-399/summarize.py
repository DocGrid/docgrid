"""Gradle 테스트 결과 XML을 클래스별로 집계한다.

사용법: python3 summarize.py <build/test-results/test 디렉터리>
"""
import glob
import sys
import xml.etree.ElementTree as ET

if len(sys.argv) != 2:
    sys.exit("사용법: python3 summarize.py <build/test-results/test 디렉터리>")

directory = sys.argv[1]
paths = sorted(glob.glob(directory + "/*.xml"))
if not paths:
    sys.exit(f"테스트 결과 XML이 없습니다: {directory}")

totals = [0, 0, 0, 0]
rows = []
for path in paths:
    root = ET.parse(path).getroot()
    counts = [int(root.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")]
    totals = [a + b for a, b in zip(totals, counts)]
    rows.append((root.get("name").split(".")[-1], counts))
for name, c in rows:
    print(f"{name:70s} tests={c[0]:<4} fail={c[1]:<3} err={c[2]:<3} skipped={c[3]}")
print("합계 tests=%d failures=%d errors=%d skipped=%d" % tuple(totals))
