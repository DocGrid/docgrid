#!/usr/bin/env python3
"""#545 실행 계획 증빙용 psql 스크립트를 만든다. 표준 라이브러리만 쓴다.

운영 VectorSearchRepository.findTopK SQL을 그대로 PREPARE하고, 시험이 쓴 허용 문서 ID와 질문 Vector로 EXECUTE한다.
Custom Plan과 Generic Plan, HNSW 강제(enable_sort=off) 계획을 EXPLAIN ANALYZE로 비교한다.

사용법 (시험 DB에 50,000 청크 데이터가 적재된 상태에서):
    python3 explain_plans.py <backend 폴더> <plans.json> > plans.sql
    docker exec -i <격리 DB 컨테이너> psql -U <user> -d <db> -q < plans.sql > plans-raw.txt
    python3 explain_plans.py --mask plans-raw.txt > plans-psql.txt
"""
import json
import re
import sys

SCHEMA = "docgrid_permission_recall_545"
QUESTION_ID = "D01"
CASES = [
    # (제목, 허용 비율, LIMIT(topK×4), SET LOCAL 목록)
    ("허용 100% · topK 10 · 현재 계획 · Custom Plan", 1.0, 40, ["plan_cache_mode = force_custom_plan"]),
    ("허용 100% · topK 10 · 현재 계획 · Generic Plan", 1.0, 40, ["plan_cache_mode = force_generic_plan"]),
    ("허용 10% · topK 10 · 현재 계획 · Custom Plan", 0.1, 40, ["plan_cache_mode = force_custom_plan"]),
    ("허용 10% · topK 10 · 현재 계획 · Generic Plan", 0.1, 40, ["plan_cache_mode = force_generic_plan"]),
    ("허용 100% · topK 20 · HNSW 강제 ef40", 1.0, 80,
     ["plan_cache_mode = force_custom_plan", "enable_sort = off"]),
    ("허용 100% · topK 20 · HNSW 강제 iterative relaxed", 1.0, 80,
     ["plan_cache_mode = force_custom_plan", "enable_sort = off", "hnsw.iterative_scan = relaxed_order"]),
    ("허용 10% · topK 10 · HNSW 강제 ef40", 0.1, 40,
     ["plan_cache_mode = force_custom_plan", "enable_sort = off"]),
    ("허용 10% · topK 10 · HNSW 강제 iterative relaxed", 0.1, 40,
     ["plan_cache_mode = force_custom_plan", "enable_sort = off", "hnsw.iterative_scan = relaxed_order"]),
]


def permitted_ids(plans, ratio):
    """시험이 남긴 Exact 계획의 Index Cond에서 해당 비율의 허용 문서 ID를 읽는다."""
    for sample in plans:
        if sample["scale"] == "50000" and sample["ratio"] == ratio and sample["method"] == "exact":
            match = re.search(r"document_id = ANY \('\{([0-9,]+)\}'", sample["plan"])
            if match:
                return [int(value) for value in match.group(1).split(",")]
    raise SystemExit(f"허용 비율 {ratio}의 Exact 계획을 찾지 못했습니다.")


def build(backend, plans_path):
    with open(f"{backend}/build/reports/permission-recall/question-vectors.json", encoding="utf-8") as handle:
        vectors = json.load(handle)["vectors"]
    with open(f"{backend}/../docs/test-results/evidence/issue-545/questions.json", encoding="utf-8") as handle:
        question = next(q["question"] for q in json.load(handle)["questions"] if q["id"] == QUESTION_ID)
    with open(plans_path, encoding="utf-8") as handle:
        plans = json.load(handle)
    source = open(f"{backend}/src/main/java/com/opensource/docgrid/domain/search/repository/VectorSearchRepository.java",
                  encoding="utf-8").read()
    sql = re.search(r'@Query\(value = """(.*?)""", nativeQuery = true\)\s*List<VectorSearchRow> findTopK',
                    source, re.S).group(1)
    vector = "[" + ",".join(repr(float(value)) for value in vectors[question]) + "]"

    print(f"SET search_path = {SCHEMA}, public;")
    prepared = {}
    for title, ratio, limit, settings in CASES:
        if ratio not in prepared:
            ids = permitted_ids(plans, ratio)
            name = f"top_k_{len(prepared)}"
            placeholders = ", ".join(f"${index + 3}" for index in range(len(ids)))
            statement = (sql.replace(":queryVector", "$1").replace(":modelId", "$2")
                         .replace("(:permittedIds)", f"({placeholders})").replace(":topK", f"${len(ids) + 3}"))
            types = "text, bigint, " + ", ".join(["bigint"] * len(ids)) + ", int"
            print(f"PREPARE {name}({types}) AS {statement};")
            prepared[ratio] = (name, ids)
        name, ids = prepared[ratio]
        print(f"\\echo '=== {title} (허용 문서 {len(ids)}건, LIMIT {limit})'")
        print("BEGIN;")
        for setting in settings:
            print(f"SET LOCAL {setting};")
        values = f"'{vector}', 1, " + ", ".join(map(str, ids)) + f", {limit}"
        print(f"EXPLAIN (ANALYZE, BUFFERS) EXECUTE {name}({values});")
        print("ROLLBACK;")

    # 기본 plan_cache_mode(auto)에서 같은 Prepared Statement를 반복하면 몇 번째부터 Generic Plan으로 바뀌는지 본다.
    name, ids = prepared[1.0]
    values = f"'{vector}', 1, " + ", ".join(map(str, ids)) + ", 40"
    print(f"DEALLOCATE {name};")
    placeholders = ", ".join(f"${index + 3}" for index in range(len(ids)))
    types = "text, bigint, " + ", ".join(["bigint"] * len(ids)) + ", int"
    statement = (sql.replace(":queryVector", "$1").replace(":modelId", "$2")
                 .replace("(:permittedIds)", f"({placeholders})").replace(":topK", f"${len(ids) + 3}"))
    print(f"PREPARE {name}({types}) AS {statement};")
    print("SET plan_cache_mode = auto;")
    for run in range(1, 9):
        print(f"\\echo '=== auto 모드 반복 {run}회째 · 허용 100% · topK 10'")
        print(f"EXPLAIN (ANALYZE, SUMMARY) EXECUTE {name}({values});")

    # 권고안 검증: 질문 Vector를 스칼라 서브쿼리(InitPlan)로 한 번만 계산하면 Generic Plan에서도 행마다 다시 계산하지 않는다.
    rewritten = statement.replace("CAST($1 AS vector)", "(SELECT CAST($1 AS vector))")
    print(f"PREPARE init_plan({types}) AS {rewritten};")
    for mode in ("force_custom_plan", "force_generic_plan"):
        print(f"\\echo '=== 권고안 검증 · 질문 Vector InitPlan · {mode} · 허용 100% · topK 10'")
        print(f"SET plan_cache_mode = {mode};")
        print(f"EXPLAIN (ANALYZE, SUMMARY) EXECUTE init_plan({values});")
    print("RESET plan_cache_mode;")


def mask(path):
    """질문 Vector와 허용 문서 ID 목록을 줄여 사람이 읽을 수 있게 한다."""
    text = open(path, encoding="utf-8").read()
    text = re.sub(r"'\[[-0-9.,eE]+\]'(::vector)?", "'<질문 벡터 1024차원>'", text)
    text = re.sub(r"'\{[0-9,]+\}'::bigint\[\]", "'<허용 문서 ID 목록>'::bigint[]", text)
    text = re.sub(r"ARRAY\[\$3(, \$[0-9]+)+\]", "ARRAY[$3, ..., $n]", text)
    # psql 표 머리글은 가리기 전 가장 긴 줄 폭으로 채워져 있으므로 구분선·빈 머리글을 지우고 줄 끝 공백을 없앤다.
    lines = [line.rstrip() for line in text.splitlines()]
    kept = [line for line in lines if line and not set(line) <= {"-", "+"} and line.strip() != "QUERY PLAN"]
    print("\n".join(kept))


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--mask":
        mask(sys.argv[2])
    elif len(sys.argv) == 3:
        build(sys.argv[1], sys.argv[2])
    else:
        print(__doc__)
        sys.exit(2)
