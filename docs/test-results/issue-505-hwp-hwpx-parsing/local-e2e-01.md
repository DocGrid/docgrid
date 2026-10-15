# `hwp-hwpx-local-e2e-20261015-01`

- 시각: 2026-10-15 22:38:44~22:39:00 KST.
- 위치·개정: 격리 로컬 작업 트리 `feature/505`, 아직 커밋하지 않은 구현. PostgreSQL 17·MinIO·Valkey·BGE-M3 컨테이너.
- 목적: HWP/HWPX 실제 Multipart 업로드→Worker 인덱싱→표 텍스트 Chunk·벡터 검색 및 기존 PDF/DOCX 회귀 확인.
- 명령: 시험 전용 JWT·MinIO 설정과 `REDIS_PORT=6381`을 주입한 `./backend/gradlew -p backend localE2eTest --tests com.opensource.docgrid.e2e.LocalDocumentIndexingE2ETest --console plain`. 자격 증명 값은 기록하지 않았다.
- 합격 기준: HWP/HWPX 각각 `INDEXED`, 표 셀 문구를 포함한 Chunk·활성 임베딩·검색 후보 확인. PDF/DOCX 기존 시험도 통과. 전용 Schema·Bucket 정리.
- 관측: `BUILD SUCCESSFUL in 16s`. JUnit XML 기준 **2개 실행, 2개 통과, 실패 0개, 건너뜀 0개**. HWP/HWPX 시험 **3.895초**, PDF/DOCX 시험 **5.34초**. 테스트의 `@AfterAll` 정리 경로가 오류 없이 종료됐고, 이후 DB 조회에서 전용 Schema가 없는 것을 확인했다.
- 정리: 이번에 기동한 PostgreSQL·MinIO·Valkey·임베딩 서버 컨테이너 4개를 중지했다. 이미지와 데이터 볼륨은 남겼다.
- 판정: **로컬 기능 E2E 통과**. 운영 OpenSQL·GCS에서 실행한 시험은 아니므로 그 환경의 동작을 입증하지 않는다.
