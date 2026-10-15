# #505 HWP·HWPX 파싱 검증 실행별 기록

이 문서의 수치는 2026-10-15 KST 로컬 작업 트리에서 실제 실행한 명령의 출력과 JUnit XML 결과를 구분해 요약한 것이다. 초기 짧은 실행의 정확한 시작 시각은 별도 수집하지 않아 날짜만 표시한다. 각 실행은 별도 파일로 보존하며, 뒤의 성공으로 앞선 실패를 지우지 않는다.

| 실행 ID | 목적 | 관측 결과 | 상세 기록 |
| --- | --- | --- | --- |
| `hwp-hwpx-unit-20261015-01` | 최초 파서·파일 검증 | 24개 중 1개 실패: 일반 미리보기 텍스트를 XML로 오인 | [01](unit-01.md) |
| `hwp-hwpx-unit-20261015-02` | XML 판별 수정 | 24개 통과 | [02](unit-02.md) |
| `hwp-hwpx-unit-20261015-03` | 암호화 표시 오류 추가 | 26개 통과 | [03](unit-03.md) |
| `hwp-hwpx-backend-20261015-01` | 백엔드 전체 회귀 | 1,468개 중 47개 실패, 5개 건너뜀. 로컬 `JWT_SECRET` 부재와 신규 HWPX 경로 검사 과잉 포함 | [전체](backend-full-01.md) |
| `hwp-hwpx-backend-20261015-02` | JWT 시험 설정 후 백엔드 전체 회귀 | 1,423개 중 21개 실패, 5개 건너뜀. PostgreSQL 연결 17건과 후속 Context 캐시 실패 4건 | [재실행](backend-full-02.md) |
| `hwp-hwpx-unit-20261015-04` | HWPX 경로 검사 수정 | 28개 중 2개 실패: 정상 미리보기 rootfile 오인 | [04](unit-04.md) |
| `hwp-hwpx-unit-20261015-05` | 패키지 MIME 한정 검사 | 28개 통과 | [05](unit-05.md) |
| `hwp-hwpx-unit-20261015-06` | 스트림 종료 수정 후 재검증 | 28개 통과 | [06](unit-06.md) |
| `hwp-hwpx-frontend-20261015-01` | 프런트엔드 빌드·회귀 | 빌드 성공, 49개 통과 | [프런트엔드](frontend-01.md) |
| `hwp-hwpx-frontend-lint-20261015-01` | 프런트엔드 정적 검사 | ESLint 오류 0개 | [lint](frontend-lint-01.md) |
| `hwp-hwpx-package-20261015-01` | 최초 실행 JAR 확인 | 빌드 성공, 두 라이브러리 포함, 라이선스 고지 누락 발견 | [패키징 01](package-01.md) |
| `hwp-hwpx-package-20261015-02` | 라이선스 고지 추가 후 실행 JAR 확인 | 빌드 성공, 라이선스 사본·출처·두 라이브러리 포함 | [패키징 02](package-02.md) |
| `hwp-hwpx-e2e-compile-20261015-01` | 표 청크 검색 E2E 코드 컴파일 | 컴파일 성공, 실제 E2E 실행은 아님 | [컴파일](compile-e2e-01.md) |
| `hwp-hwpx-e2e-readiness-20261015-01` | Docker 엔진 준비 확인 | Docker API 500 오류, 컨테이너·E2E 미실행 | [준비 점검](e2e-readiness-01.md) |
| `hwp-hwpx-e2e-readiness-20261015-02` | Docker 엔진 재확인 | Docker API 500 오류 재발, E2E 미실행 | [재확인](e2e-readiness-02.md) |
| `hwp-hwpx-e2e-readiness-20261015-03` | Docker Desktop·엔진 상태 분리와 정상 재시작 | 엔진 HTTP 500 → 재시작 뒤 Docker 서버 29.4.1 응답 | [복구 점검](e2e-readiness-03.md) |
| `hwp-hwpx-e2e-env-20261015-01` | 로컬 E2E 컨테이너 최초 기동 | 6379 포트 점유로 Valkey 기동 실패 | [환경 01](e2e-env-01.md) |
| `hwp-hwpx-e2e-env-20261015-02` | Valkey 포트 분리 후 서비스 확인 | 네 서비스 healthy, Valkey PONG, 임베딩 HTTP 200 | [환경 02](e2e-env-02.md) |
| `hwp-hwpx-local-e2e-20261015-01` | HWP/HWPX 및 PDF/DOCX 로컬 전체 경로 | **2/2 통과**, 실패 0개 | [로컬 E2E](local-e2e-01.md) |
| `hwp-hwpx-backend-20261015-03` | 인프라 준비 후 기본 백엔드 전체 회귀 | 1,489개 중 4개 실패, 5개 건너뜀. 로컬 DB 연결 상한 초과 | [전체 03](backend-full-03.md) |
| `hwp-hwpx-backend-20261015-04` | 시험용 연결 풀 제한 후 기본 전체 회귀 | **1,489개 실행, 실패 0개, 5개 건너뜀** | [전체 04](backend-full-04.md) |

실제 PostgreSQL·MinIO·BGE-M3를 쓰는 HWP/HWPX 업로드→`INDEXED`→pgvector 검색 E2E는 Docker Desktop 재시작과 로컬 인프라 준비 뒤 실행해 통과했다. 이는 운영 OpenSQL·GCS 시험과 구분한다.
