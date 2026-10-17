#!/usr/bin/env python3
"""#549 수정 전후 SQL의 Custom/Generic Plan 실행 시간을 psql로 비교하는 스크립트를 만든다. 표준 라이브러리만 쓴다.

현재(수정 후) VectorSearchRepository.findTopK SQL을 읽고, 질문 Vector 서브쿼리를 원래의 CAST로 되돌린 SQL을 수정 전으로 쓴다.
두 SQL을 같은 질문(D01)·허용 문서(5만 청크 100%, 10%)로 PREPARE하고 다음을 EXPLAIN ANALYZE한다.
  - force_custom_plan / force_generic_plan
  - 기본 plan_cache_mode(auto)에서 같은 Statement 8회 반복

사용법 (#545 하네스로 5만 청크 데이터가 적재된 상태에서):
    python3 explain_generic_plan.py <backend 폴더> <plans.json> > generic.sql
    docker exec -i <격리 DB 컨테이너> psql -U <user> -d <db> -q < generic.sql > generic-raw.txt
    python3 explain_generic_plan.py --mask generic-raw.txt > generic-plan-psql.txt
"""
import json
import re
import sys

SCHEMA = "docgrid_permission_recall_545"
QUESTION = "재택근무는 한 주에 몇 번까지 할 수 있어?"
FIXED_CAST = "(SELECT CAST(:queryVector AS vector))"
ORIGINAL_CAST = "CAST(:queryVector AS vector)"


def permitted_ids(plans, ratio):
    for sample in plans:
        if sample["scale"] == "50000" and sample["ratio"] == ratio and sample["method"] == "exact":
            match = re.search(r"document_id = ANY \('\{([0-9,]+)\}'", sample["plan"])
            if match:
                return [int(value) for value in match.group(1).split(",")]
    raise SystemExit(f"허용 비율 {ratio}의 Exact 계획을 찾지 못했습니다.")


def prepared(name, sql, ids):
    placeholders = ", ".join(f"${index + 3}" for index in range(len(ids)))
    statement = (sql.replace(":queryVector", "$1").replace(":modelId", "$2")
                 .replace("(:permittedIds)", f"({placeholders})").replace(":topK", f"${len(ids) + 3}"))
    types = "text, bigint, " + ", ".join(["bigint"] * len(ids)) + ", int"
    return f"PREPARE {name}({types}) AS {statement};"


def build(backend, plans_path):
    with open(f"{backend}/build/reports/permission-recall/question-vectors.json", encoding="utf-8") as handle:
        vector = "[" + ",".join(repr(float(v)) for v in json.load(handle)["vectors"][QUESTION]) + "]"
    with open(plans_path, encoding="utf-8") as handle:
        plans = json.load(handle)
    source = open(f"{backend}/src/main/java/com/opensource/docgrid/domain/search/repository/VectorSearchRepository.java",
                  encoding="utf-8").read()
    fixed = re.search(r'@Query\(value = """(.*?)""", nativeQuery = true\)\s*List<VectorSearchRow> findTopK',
                      source, re.S).group(1)
    if FIXED_CAST not in fixed:
        raise SystemExit("현재 findTopK SQL에 수정(질문 Vector 서브쿼리)이 없습니다.")
    versions = [("수정 전", fixed.replace(FIXED_CAST, ORIGINAL_CAST)), ("수정 후", fixed)]

    print(f"SET search_path = {SCHEMA}, public;")
    for ratio, label in ((1.0, "허용 100%"), (0.1, "허용 10%")):
        ids = permitted_ids(plans, ratio)
        values = f"'{vector}', 1, " + ", ".join(map(str, ids)) + ", 40"
        for version_index, (version, sql) in enumerate(versions):
            name = f"s_{int(ratio * 1000)}_{version_index}"
            print(prepared(name, sql, ids))
            for mode in ("force_custom_plan", "force_generic_plan"):
                print(f"\\echo '=== {version} · {label} · topK 10 · {mode}'")
                print(f"SET plan_cache_mode = {mode};")
                print(f"EXPLAIN (ANALYZE, BUFFERS) EXECUTE {name}({values});")
            print(f"DEALLOCATE {name};")
            if ratio == 1.0:
                # 새 Statement를 auto 모드로 8번 반복해 몇 번째부터 Generic Plan으로 바뀌는지 본다.
                print(prepared(name, sql, ids))
                print("SET plan_cache_mode = auto;")
                for run in range(1, 9):
                    print(f"\\echo '=== {version} · {label} · auto 모드 반복 {run}회째'")
                    print(f"EXPLAIN (ANALYZE, SUMMARY) EXECUTE {name}({values});")
                print(f"DEALLOCATE {name};")
    print("RESET plan_cache_mode;")


def mask(path):
    text = open(path, encoding="utf-8").read()
    text = re.sub(r"'\[[-0-9.,eE]+\]'(::vector)?", "'<질문 벡터 1024차원>'", text)
    text = re.sub(r"'\{[0-9,]+\}'::bigint\[\]", "'<허용 문서 ID 목록>'::bigint[]", text)
    text = re.sub(r"ARRAY\[\$3(, \$[0-9]+)+\]", "ARRAY[$3, ..., $n]", text)
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
