# 공개 UI 문서의 범위 제한 Worker 대조 — `worker471-scoped-r1`

- 목적: 화면에서 만든 문서 #115만 처리하고 기존 대기 Job을 움직이지 않았는지 DB에서 확인한다.
- 위치: GCP 앱 A의 일시적 시험 프로필과 pgJDBC 읽기 전용 대조. 2026-10-10 약 05:14~05:20 KST.
- 성공 기준: 버전 #117의 Job #116이 최종 `INDEXED`, 청크·벡터 1개 이상, 기존 Job 지문 불변, 앱 A 원복·B 불변.

| 순서·실행 위치 | 명령·방법 | 관측 결과 | 판정 |
| --- | --- | --- | --- |
| 1. 앱 A JVM | 앱과 동일한 pgJDBC 드라이버로 `pg_is_in_recovery()`와 시험 ID의 Job·청크·벡터, 기존 Job 집계를 읽기 전용 조회 | primary 도착, Job #116 `PENDING`, 버전 #117, 청크 0·벡터 0; 기존 Job 81건·Attempt 85건, 상태 SHA-256 `c5a0fff4c38a8cbe934a9a3b79a86c0c7bde6c22a4eed69f91afc0abfd1fe579` | 안전 기준선 |
| 2. 앱 A VM | 범위 제한 JAR SHA-256 `10e3b4776d00c65e9fba32b9194dfb8cc5775d6b5a4074e0cf8c7df9cb3dc744`를 일시 배포하고 `worker-scope-test`·버전 #117 필터만 활성화 | 앱 A만 Worker 활성; 앱 B는 기존 JAR·Worker 비활성 | 시험 범위 제한 |
| 3. 앱 A JVM | 같은 pgJDBC로 Job·Attempt·버전·청크·벡터 최종 조회 | Job #116 `INDEXED`, retry 0, 성공 Attempt 1건·처리 3,748 ms, 청크 1·벡터 1×1024차원, 잘못된 차원 0·중복 키 0 | 목표 문서 처리 통과 |
| 4. 앱 A JVM | 기존 Job 전체 상태 지문·Attempt 수 재대조 | 기존 81건 지문 `c5a0fff4c38a8cbe934a9a3b79a86c0c7bde6c22a4eed69f91afc0abfd1fe579` 그대로, 기존 Attempt 85건 그대로 | 기존 큐 불변 |
| 5. 앱 A/B VM | 앱 A 기존 JAR·`Worker=false` 원복, readiness; B의 기존 상태 확인 | 앱 A/B readiness 200, 둘 다 원래 JAR·Worker 비활성; A의 중간 설정 백업·일회성 전송 파일 제거 | 원복 통과 |

DB 직접 `psycopg2` 접속은 이전 실행에서 인증 실패였으므로, 이번 대조는 앱과 동일한 pgJDBC 경로를 사용했다. 비밀값과 비공개 인프라 식별자는 출력·보존하지 않았다.
