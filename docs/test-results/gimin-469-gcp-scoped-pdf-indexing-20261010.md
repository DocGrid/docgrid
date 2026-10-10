# GCP 시험 PDF의 범위 제한 Worker 인덱싱·검색·다운로드 검증

- 이슈: #469
- 실행 코드: `d28c202f01251a945410ef6598be8cfb48671d5e`의 범위 제한 Worker; 앱 A에 일시 배포 후 기존 JAR로 원복
- 결론: **시험 문서 1건 E2E 통과 / 기존 Job 80건 불변 / 앱 A/B 원복 완료**
- 범위 밖: 브라우저 UI 클릭, WebSocket, RAG 답변 완료, 다른 PDF 형식·동시 다중 Job, 로컬 Docker 전체 이미지 빌드

## 시험 경로와 완료 기준

```text
공개 Vercel 프런트 API → Cloud Run 진입점 → 앱 A/B → OpenProxy → OpenSQL
                                               │                │
                                               │                └─ 시험 Job 115
                                               └─ GCS 원본 PDF / CPU 임베딩 서버

앱 A만 새 JAR + worker-scope-test + 버전 116 필터로 일시 실행
앱 B는 기존 JAR + Worker=false를 유지
```

업로드 201 → Job 115만 `INDEXED` → 청크·벡터 생성 → 검색 결과에 문서 114 포함 → GCS 다운로드 해시 동일 → 앱 A 원복을 순서대로 판정했다. 기존 큐는 삭제하거나 일괄 처리하지 않았다.

## 실행별 결과

| 실행 ID | 위치·명령 또는 절차 | 핵심 숫자·상태 | 판정·해석 |
| --- | --- | --- | --- |
| `pdf469-upload-r1` | 공개 API에서 합성 PDF 가입·로그인·업로드 | 200/201/200/201, 17,208 bytes, 문서 114·버전 116·Job 115 | 실제 공개 HTTP 업로드 성공; 처리 전 Job `PENDING`. [개별 로그](evidence/issue-469/pdf469-upload-r1.md) |
| `worker469-scoped-r1` | GCP 앱 A만 보호용 프로필·버전 필터로 재시작, pgJDBC로 DB 대조 | Job `INDEXED`, retry 0, Attempt 1, 청크 1, 벡터 1×1024, 중복 0, 기존 80 Job 지문 동일 | 범위 제한 Worker 처리 통과. `psycopg2` 인증 실패 후 앱과 같은 JDBC로 대조했다. [개별 로그](evidence/issue-469/worker469-scoped-r1.md) |
| `pdf469-result-r1` | 공개 API 검색·상태·원본 다운로드 | 검색 200·2결과 중 시험 문서 1, 다운로드 200·17,208 bytes·SHA-256 일치 | 사용 가능한 검색·다운로드 경로 확인. RAG 답변은 아직 `PROCESSING`. [개별 로그](evidence/issue-469/pdf469-result-r1.md) |

## 상태 전이와 안전성

```text
문서 버전 116      UPLOADED ───────────▶ INDEXED
Job 115           PENDING  ──A만 claim▶ INDEXED (Attempt 1, retry 0)
DB 청크·벡터       0 / 0              ▶ 1 / 1 (1024차원)
기존 Job 80건     상태 지문 고정       ▶ 같은 SHA-256 지문
기존 Attempt      84건               ▶ 84건
앱 A Worker       false → true(버전 116만) → false
앱 B Worker       false ─────────────────▶ false
```

완료 Job에 Worker 소유자 ID가 남는 것은 현재 `EmbeddingJob.markIndexed()`의 감사용 소유권 보존 규칙이다. 이 값만 보고 미완료 또는 lease 회수 실패로 분류하지 않았다.

## 실패·한계·원복

- 최초 DB 직접 대조 방식(`psycopg2` → OpenProxy)은 인증 단계에서 거부되었다. 앱이 쓰는 pgJDBC 드라이버로 같은 조회를 재실행하여 primary와 결과를 확인했다. 실패 실행을 성공으로 합산하지 않는다.
- 로컬 Docker 엔진은 API 500을 반환해 이 턴의 PostgreSQL 통합 테스트를 돌리지 못했다. 기존 Java 단위 테스트 31건은 범위 제한 코드 PR에서 통과했지만, 본 문서의 실측은 GCP 실행에 한정한다.
- 앱 A는 기존 JAR SHA-256 `a321b1e6f09d8c805c8bd0f58f641916db9a565286cc7e2655d4bedd8ff5de89`, `Worker=false`, readiness 200으로 복원했다. B도 동일 JAR·`Worker=false`·readiness 200이다. 원복 확인 후 A의 중복 비밀 설정 백업과 일회성 전송 파일을 삭제했다.
- 계정 비밀번호·토큰, 내부 네트워크 식별자, 원본 검색 본문은 증거에 기록하지 않았다. 시험 계정의 접근 권한은 일반 사용자 수준이며 별도 UI 시험에 재사용한 뒤 정리해야 한다.
- 이번 결과는 합성 PDF 한 건의 관측이다. 모든 문서·임베딩 실패·장애 복구에 대한 보장이 아니다.
