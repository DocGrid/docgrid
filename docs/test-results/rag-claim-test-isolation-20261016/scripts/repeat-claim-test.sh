#!/bin/bash
# RagResponseClaimIntegrationTest를 CI와 같은 클래스 순서로 반복 실행하고 결과를 요약한다(#520).
#
# 사용: scripts/repeat-claim-test.sh <반복 횟수> [forced]
#   - 저장소 루트에서 실행한다. DB_HOST·DB_PORT·REDIS_HOST·REDIS_PORT·JWT_SECRET 등은 격리 DB 값으로 미리 export한다.
#   - forced: 각 시험의 작업 생성 직후 Thread.sleep(1500)을 넣어 경합 창을 RAG Worker polling 주기(1s)보다 넓힌다.
#   - 시험 파일은 실행 동안만 바꾸고, 종료 시 원래 내용으로 되돌린다.
set -u

REPS=${1:?반복 횟수를 지정하세요}
MODE=${2:-natural}
FILE=backend/src/test/java/com/opensource/docgrid/domain/rag/integration/RagResponseClaimIntegrationTest.java
RESULT=backend/build/test-results/test/TEST-com.opensource.docgrid.domain.rag.integration.RagResponseClaimIntegrationTest.xml
BACKUP=$(mktemp)

cp "$FILE" "$BACKUP"
trap 'cp "$BACKUP" "$FILE"; rm -f "$BACKUP"' EXIT

# 1. 두 시험을 N회 반복으로 바꾼다.
perl -0pi -e "s/    \@Test\n/    \@RepeatedTest($REPS)\n/g; s/(import org.junit.jupiter.api.DisplayName;\n)/\$1import org.junit.jupiter.api.RepeatedTest;\n/" "$FILE"

# 2. forced면 작업 생성 직후 경합 창을 넓힌다.
if [ "$MODE" = "forced" ]; then
    perl -0pi -e 's/(        Long secondJobId = createPendingJob\(\);\n)/$1        Thread.sleep(1500);\n/; s/(        Long jobId = createPendingJob\(\);\n)/$1        Thread.sleep(1500);\n/' "$FILE"
fi

# 3. 실제 RAG Worker가 살아 있는 기본 Context 클래스 두 개를 먼저 실행한다(CI와 같은 순서).
./backend/gradlew -p backend test \
    --tests '*RagJobWorkerConcurrentQueueIntegrationTest' \
    --tests '*RagJobWorkerIntegrationTest' \
    --tests '*RagResponseClaimIntegrationTest' \
    --console=plain > /dev/null 2>&1
echo "gradle exit=$?"

grep -o '<testsuite [^>]*>' "$RESULT" | grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"'
echo "skipsLockedRow 실패: $(grep -A3 '<failure' "$RESULT" | grep -c 'at .*skipsLockedRow')"
echo "claim 실패: $(grep -A3 '<failure' "$RESULT" | grep -c 'at .*claim_allowsExactlyOneConcurrentOwner')"
echo "외부 Worker 로그: $(grep -cE 'ag-worker-job|\[RAG\] fallback' "$RESULT")"
