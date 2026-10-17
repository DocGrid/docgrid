#!/usr/bin/env python3
"""#549 같은 HNSW Index 위에서 수정 전/후 findTopK SQL을 번갈아 실행해 결과와 실행 시간을 비교한다. 표준 라이브러리만 쓴다.

전체 재측정은 실행마다 HNSW Index를 새로 만들므로, HNSW 강제 방식의 결과 차이가 SQL 수정 때문인지 Index 재생성 때문인지
구분할 수 없다. 이 스크립트는 한 Index에서 두 SQL을 비교한다.
  1) 결과: 질문 30개 × 허용 10%·100% × {HNSW 강제 ef40, iterative relaxed} × {수정 전, 수정 후}의 청크 ID 순서
  2) 시간: Custom Plan·HNSW 강제 계획을 수정 전/후 번갈아 15회씩 EXPLAIN (ANALYZE, TIMING OFF)한 Execution Time 중앙값

사용법 (#545 하네스로 5만 청크 데이터가 적재된 상태에서):
    python3 same_index_check.py build <backend 폴더> <plans.json> > check.sql
    docker exec -i <격리 DB 컨테이너> psql -U <user> -d <db> -qAt < check.sql > check-raw.txt
    python3 same_index_check.py report check-raw.txt > same-index-check.md
"""
import json
import re
import statistics
import sys
from collections import defaultdict

SCHEMA = "docgrid_permission_recall_545"
FIXED_CAST = "(SELECT CAST(:queryVector AS vector))"
ORIGINAL_CAST = "CAST(:queryVector AS vector)"
MODES = {
    "hnsw_ef40": ["enable_sort = off", "hnsw.ef_search = 40"],
    "iterative_relaxed": ["enable_sort = off", "hnsw.ef_search = 40", "hnsw.iterative_scan = relaxed_order"],
}
TIMING_CASES = [
    ("현재 계획 Custom · 허용 100%", 1.0, []),
    ("현재 계획 Custom · 허용 10%", 0.1, []),
    ("현재 계획 Custom · 허용 0.1%", 0.001, []),
    ("HNSW 강제 ef40 · 허용 100%", 1.0, MODES["hnsw_ef40"]),
    ("HNSW 강제 iterative relaxed · 허용 10%", 0.1, MODES["iterative_relaxed"]),
]
TIMING_RUNS = 15
LIMIT = 40


def permitted_ids(plans, ratio):
    for sample in plans:
        if sample["scale"] == "50000" and sample["ratio"] == ratio and sample["method"] == "exact":
            match = re.search(r"document_id = ANY \('\{([0-9,]+)\}'", sample["plan"])
            if match:
                return [int(value) for value in match.group(1).split(",")]
    raise SystemExit(f"허용 비율 {ratio}의 Exact 계획을 찾지 못했습니다.")


def literal_sql(sql, vector, ids):
    return (sql.replace(":queryVector", f"'{vector}'").replace(":modelId", "1")
            .replace("(:permittedIds)", "(" + ",".join(map(str, ids)) + ")").replace(":topK", str(LIMIT)))


def build(backend, plans_path):
    with open(f"{backend}/build/reports/permission-recall/question-vectors.json", encoding="utf-8") as handle:
        vectors = json.load(handle)["vectors"]
    with open(f"{backend}/../docs/test-results/evidence/issue-545/questions.json", encoding="utf-8") as handle:
        questions = json.load(handle)["questions"]
    with open(plans_path, encoding="utf-8") as handle:
        plans = json.load(handle)
    source = open(f"{backend}/src/main/java/com/opensource/docgrid/domain/search/repository/VectorSearchRepository.java",
                  encoding="utf-8").read()
    fixed = re.search(r'@Query\(value = """(.*?)""", nativeQuery = true\)\s*List<VectorSearchRow> findTopK',
                      source, re.S).group(1)
    if FIXED_CAST not in fixed:
        raise SystemExit("현재 findTopK SQL에 수정(질문 Vector 서브쿼리)이 없습니다.")
    forms = {"before": fixed.replace(FIXED_CAST, ORIGINAL_CAST), "after": fixed}

    print(f"SET search_path = {SCHEMA}, public;")
    print("SET plan_cache_mode = force_custom_plan;")
    # 1) 결과 비교: 같은 Index에서 수정 전/후 SQL의 청크 ID 순서
    for ratio in (1.0, 0.1):
        ids = permitted_ids(plans, ratio)
        for question in questions:
            vector = "[" + ",".join(repr(float(v)) for v in vectors[question["question"]]) + "]"
            for mode, settings in MODES.items():
                for form, sql in forms.items():
                    print("BEGIN;")
                    for setting in settings:
                        print(f"SET LOCAL {setting};")
                    print(f"SELECT 'R|{ratio}|{question['id']}|{mode}|{form}|' || "
                          f"coalesce(string_agg(chunk_id::text, ',' ORDER BY distance, chunk_id), '') "
                          f"FROM ({literal_sql(sql, vector, ids)}) t;")
                    print("ROLLBACK;")
    # 2) 시간 비교: 질문 D01, Custom Plan, 수정 전/후를 번갈아 실행
    vector = "[" + ",".join(repr(float(v)) for v in vectors[questions[0]["question"]]) + "]"
    for case_index, (title, ratio, settings) in enumerate(TIMING_CASES):
        ids = permitted_ids(plans, ratio)
        for run in range(TIMING_RUNS):
            for form, sql in forms.items():
                print("BEGIN;")
                for setting in settings:
                    print(f"SET LOCAL {setting};")
                print(f"SELECT 'T|{case_index}|{form}|{run}';")
                print(f"EXPLAIN (ANALYZE, TIMING OFF, SUMMARY ON) {literal_sql(sql, vector, ids)};")
                print("ROLLBACK;")


def report(path):
    results = defaultdict(dict)
    timings = defaultdict(lambda: defaultdict(list))
    current = None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("R|"):
            _, ratio, qid, mode, form, ids = line.split("|", 5)
            results[(ratio, qid, mode)][form] = ids
        elif line.startswith("T|"):
            _, case_index, form, _ = line.split("|")
            current = (int(case_index), form)
        elif line.startswith("Execution Time:") and current:
            timings[current[0]][current[1]].append(float(line.split(":")[1].split()[0]))
            current = None

    print("### 같은 Index에서 결과 비교 (질문 30개)\n")
    print("| 허용 비율 | 방식 | 수정 전/후 결과가 같은 질문 |")
    print("|---:|---|---:|")
    grouped = defaultdict(lambda: [0, 0])
    for (ratio, _, mode), forms in results.items():
        grouped[(ratio, mode)][1] += 1
        if forms.get("before") == forms.get("after"):
            grouped[(ratio, mode)][0] += 1
    for (ratio, mode), (same, total) in sorted(grouped.items(), key=lambda item: (-float(item[0][0]), item[0][1])):
        print(f"| {float(ratio) * 100:g}% | {mode} | {same}/{total} |")

    print(f"\n### 같은 Index에서 실행 시간 비교 (수정 전/후 번갈아 {TIMING_RUNS}회, Execution Time 중앙값)\n")
    print("| 경우 | 수정 전 (ms) | 수정 후 (ms) | 차이 (ms) | 변화율 |")
    print("|---|---:|---:|---:|---:|")
    for case_index, (title, _, _) in enumerate(TIMING_CASES):
        before = statistics.median(timings[case_index]["before"])
        after = statistics.median(timings[case_index]["after"])
        print(f"| {title} | {before:.3f} | {after:.3f} | {after - before:+.3f} | {(after - before) / before * 100:+.1f}% |")


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "build":
        build(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 3 and sys.argv[1] == "report":
        report(sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)
