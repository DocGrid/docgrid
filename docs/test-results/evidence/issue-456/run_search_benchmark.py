#!/usr/bin/env python3
"""하이브리드 검색 전후 비교용 측정 스크립트 (#456).

실행 중인 백엔드에 questions.json의 질문을 순서대로 보내고, 정답 청크가 몇 위에 나오는지로
Hit@k·MRR@10과 정답 없는 질문의 반환 수를 계산한다. 접속 정보는 환경변수로만 받는다.

  BENCH_BASE_URL     백엔드 주소 (기본 http://localhost:18080)
  BENCH_EMAIL        로그인 이메일 (필수)
  BENCH_PASSWORD     로그인 비밀번호 (필수)
  BENCH_COLLECTION   검색 범위 컬렉션 ID (필수, 측정 대상 문서만 담은 컬렉션)
  BENCH_QUESTIONS    질문 파일 이름 (기본 questions-set1.json, 같은 폴더)

사용: python3 run_search_benchmark.py <결과-파일-이름-접두사>
정답은 청크 번호가 아니라 근거 문구(evidence)가 모두 들어 있는 청크로 판정한다. 질문 세트와 정답 청크 ID는
측정 대상 문서가 같은 방식으로 청킹·인덱싱된 환경에서 questions.json과 함께 생성한 값이다.
"""
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("BENCH_BASE_URL", "http://localhost:18080")
EMAIL, PASSWORD = os.environ["BENCH_EMAIL"], os.environ["BENCH_PASSWORD"]
COLLECTION = int(os.environ["BENCH_COLLECTION"])
TOP_K = 10


def post(path, body, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers=headers)
    return json.load(urllib.request.urlopen(request, timeout=120))["data"]


def summarize(rows):
    n = len(rows)
    if n == 0:
        return {}
    hit = lambda k: round(sum(1 for r in rows if r["firstRelevantRank"] and r["firstRelevantRank"] <= k) / n, 3)
    mrr = round(sum(1 / r["firstRelevantRank"] if r["firstRelevantRank"] and r["firstRelevantRank"] <= 10 else 0
                    for r in rows) / n, 3)
    return {"n": n, "Hit@1": hit(1), "Hit@3": hit(3), "Hit@5": hit(5), "MRR@10": mrr}


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "result"
    questions = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.environ.get("BENCH_QUESTIONS", "questions-set1.json")), encoding="utf-8"))
    token = post("/auth/login", {"email": EMAIL, "password": PASSWORD})["accessToken"]
    results = []
    for q in questions:
        for attempt in range(4):
            try:
                started = time.perf_counter()
                data = post("/search", {"queryText": q["question"], "topK": TOP_K, "collectionId": COLLECTION}, token)
                elapsed = (time.perf_counter() - started) * 1000
                break
            except urllib.error.HTTPError as error:
                if error.code not in (429, 502, 503, 504):
                    raise
                time.sleep(8 * (attempt + 1))
        else:
            raise SystemExit("재시도 실패 " + q["id"])
        items = [{"rank": r["rank"], "chunkId": r["chunkId"], "doc": r["documentTitle"],
                  "score": round(r["similarityScore"], 4)} for r in data["results"]]
        relevant = set(q["relevantChunkIds"])
        first = next((i["rank"] for i in items if i["chunkId"] in relevant), None)
        results.append({"id": q["id"], "type": q["type"], "question": q["question"], "firstRelevantRank": first,
                        "returned": len(items), "ms": round(elapsed, 1), "items": items})
        time.sleep(1.5)  # 질문마다 쌓이는 RAG 작업이 임베딩 서버를 지연시키지 않도록 간격을 둔다.

    json.dump(results, open(f"{tag}.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    positives = [r for r in results if r["type"] != "NO_ANSWER"]
    negatives = [r for r in results if r["type"] == "NO_ANSWER"]
    print("정답 있는 질문 전체", summarize(positives))
    for kind in ("IDENTIFIER", "PARAPHRASE", "MIXED"):
        print(" ", kind, summarize([r for r in positives if r["type"] == kind]))
    returned = [min(5, r["returned"]) for r in negatives]
    print("정답 없는 질문 반환 수(topK=5 기준)", returned, "평균", round(statistics.mean(returned), 2),
          "결과없음", sum(1 for x in returned if x == 0), "/", len(returned))
    print("응답시간 ms 중앙값", round(statistics.median(r["ms"] for r in results), 1))


if __name__ == "__main__":
    main()
