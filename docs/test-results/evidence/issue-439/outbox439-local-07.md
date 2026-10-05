# 전체 회귀 실패 클래스의 독립 재실행

- 실행 ID: `outbox439-local-07`
- 완료 시각: 2026-10-06 **01:31 KST** (JVM 종료 로그 기준), 빌드 **17초**
- 목적: 이전 전체 실행에서 실패한 기존 동시성 테스트 두 클래스가 새 격리 DB에서도 실패하는지 확인
- 위치: 새 루프백 전용 일회용 PostgreSQL 17/pgvector + 로컬 Valkey. GCP DB 접속 없음
- 성공 기준: 두 클래스의 모든 메서드 통과, 컨테이너 제거

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 새 일회용 DB 생성·SQL 준비·`vector` 확장 확인 | 기존 실패 실행의 DB를 재사용하지 않음 | DB 잔여 상태 영향을 배제 |
| `./backend/gradlew -p backend test --tests com.opensource.docgrid.domain.embedding.integration.DocumentIndexingFailureIntegrationTest --tests com.opensource.docgrid.domain.rag.integration.RagResponseClaimIntegrationTest --rerun-tasks --console=plain --no-daemon` | 인덱싱 실패 클래스 **9/9**, RAG Claim 클래스 **2/2**, 합계 **11/11 통과**, 실패 0 | 앞선 두 실패는 이 새 DB에서 재현되지 않음. 원인 확정이나 영구 안정성 보장은 아님 |
| 종료 trap 확인 | 일회용 DB 컨테이너 **0개** | 시험 자원 제거 |
