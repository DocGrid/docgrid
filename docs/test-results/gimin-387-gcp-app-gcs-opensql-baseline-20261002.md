# GCP A/B 앱·GCS·OpenSQL 정상 경로 실측 결과

관련 이슈: [#387](https://github.com/DocGrid/docgrid/issues/387)

측정일: 2026-10-02 (Asia/Seoul)

배포 기준: `develop` 커밋 `4ee00fe470510866c6fef6c711d11f644a4c69a7`
판정: **정상 경로 통과**. HA 장애 전환·인덱싱·처리량 시험으로 계산하지 않는다.

## 문제와 완료 기준

시험 전에는 A/B VM이 실행 중이어도 Java 앱이 없고 내부 LB 백엔드 2개가 모두 `UNHEALTHY`였다. GCS 어댑터 코드와 버킷만으로는 실제 앱이 OpenSQL 기록과 GCS 객체를 함께 사용하는지 알 수 없었다. 이 작업은 같은 JAR을 두 VM에 배포하고, 내부 LB·공용 Redis·두 OpenProxy·3노드 OpenSQL·GCS를 거쳐 PDF 원본을 저장·재다운로드하는 정상 상태 기준선을 만든다.

완료 조건은 A/B 동일 JAR, 앱·LB 헬스 정상, 양쪽 Redis 인증 연결, A/B/LB 각각의 PDF 업로드 진입, A/B/LB 다운로드 9회 해시 일치, DB 파일 기록 3건과 GCS 실제 객체 3건의 바이트 해시 일치다.

```text
그림 1 — 이번에 실제 기동·측정한 배치

GCP 내부 시험 VM (1대)
  ├─ HTTP 회원가입·로그인·업로드·다운로드
  ├─ A 직접 경로 ────────────────┐
  ├─ B 직접 경로 ────────────────┼──▶ APP-A / APP-B (각 2 vCPU급, 동일 JAR)
  └─ 내부 LB (백엔드 2/2 HEALTHY) ┘       │        │
                                        │        ├──▶ 공용 Redis VM (AUTH+PING)
                                        │        ├──▶ OpenProxy A/B → OpenSQL 3노드
                                        │        └──▶ GCS 버킷 (전용 VM 서비스 계정)

APP-A/B의 Flyway는 별도의 3노드 primary 탐색 JDBC 경로를 사용한다.
정상 경로 시험에는 장애 주입·인덱싱 Worker·임베딩 서버가 없다.
```

### 테스트 환경

| 구성 요소 | 수량·규격 | 이번 실행의 역할·상태 |
| --- | --- | --- |
| 백엔드 앱 VM | 2대, 각 `e2-standard-2` | 동일 JAR SHA-256, `opensql-ha` 프로필, `systemd active/enabled` |
| OpenSQL DB VM | 3대, 각 `e2-standard-2` | 문서·파일 기록; 앱은 두 OpenProxy 경유, Flyway만 DB primary 탐색 |
| 공용 Redis VM | 1대, `e2-small` | A/B에서 비밀번호 인증 후 `PING` 성공 |
| 내부 시험 VM | 1대, `e2-standard-2` | 합성 PDF를 사용해 실제 HTTP 왕복 시험 |
| 내부 관리형 LB | 1개, VM 아님 | 시험 종료 시 백엔드 `HEALTHY=2`, `UNHEALTHY=0` |
| GCS | 기존 버킷 1개 | 앱 전용 서비스 계정에 해당 버킷 `roles/storage.objectUser` 바인딩 1개 |

인스턴스 이름·사설 IP·프로젝트 ID·버킷 이름·서비스 계정 이메일·비밀번호는 공개 증거에서 제거했다. 위 규격은 해당 시점의 GCP 조회값이다. 두 앱 VM의 OAuth scope에는 `cloud-platform`이 있으며, JSON 서비스 계정 키를 생성하거나 배포하지 않았다.

## 실제 설정·코드 경로

| 구성·파일 | 이번 작업에서 사용한 역할 |
| --- | --- |
| [`application-opensql-ha.yml`](https://github.com/DocGrid/docgrid/blob/4ee00fe470510866c6fef6c711d11f644a4c69a7/backend/src/main/resources/application-opensql-ha.yml) | 앱 JDBC는 OpenProxy 2주소(각 6432), Flyway JDBC는 DB 3주소(각 5432) |
| [`application.yml`](https://github.com/DocGrid/docgrid/blob/4ee00fe470510866c6fef6c711d11f644a4c69a7/backend/src/main/resources/application.yml) | `.env` import, Redis·GCS 설정, Worker 비활성, 관리 포트 헬스 |
| [`DocumentUploadFacade`](https://github.com/DocGrid/docgrid/blob/4ee00fe470510866c6fef6c711d11f644a4c69a7/backend/src/main/java/com/opensource/docgrid/domain/document/service/DocumentUploadFacade.java) | 파일 검증·SHA 계산 뒤 GCS 후보 객체를 저장하고 DB 업로드 트랜잭션을 실행; DB 실패 시 후보 삭제 |
| [`GcsStorageService`](https://github.com/DocGrid/docgrid/blob/4ee00fe470510866c6fef6c711d11f644a4c69a7/backend/src/main/java/com/opensource/docgrid/domain/document/storage/GcsStorageService.java) | GCS 생성·읽기, 기존 키 덮어쓰기 방지, DB provider/bucket 경계 검사 |
| `scripts/opensql/verify_gcp_app_gcs_baseline.py` | 한 페이지 유효 PDF 생성, 실행별 시험 계정, HTTP 업로드·다운로드·SHA 대조; 새 스크립트 |
| `scripts/opensql/verify_gcp_app_gcs_objects.py` | OpenSQL의 파일 위치를 메모리에서만 읽고 GCS 실제 바이트를 재해시; 새 스크립트 |

```text
그림 2 — PDF 한 건의 저장·재조회 원리

시험 VM: 합성 1쪽 PDF + SHA-256
  │ POST /api/documents (A 또는 B 또는 LB)
  ▼
APP: JWT 사용자 확인
  │ DocumentUploadFacade.upload()
  ├─ FileValidationService: PDF 형식·크기 확인
  ├─ FileHashService: 원본 SHA-256 계산
  ├─ GcsStorageService.store(): GCS에 새 객체 기록
  └─ DocumentUploadService.upload(): OpenProxy → OpenSQL에
       documents / document_versions / file_objects / Job 기록
  │ 응답 201
  ▼
시험 VM: 같은 documentId로 A·B·LB 각각 GET /file
  │ DB file_objects의 GCS 위치 → GcsStorageService.read()
  └─ 받은 626~627바이트의 SHA-256 == 업로드 직전 SHA-256
```

## 장애가 아니라 설정 전제에서 발견한 첫 실패

첫 A 업로드는 회원가입 201, 로그인 200, A/B 인증 확인 200까지 진행된 뒤 HTTP 500이었다. A 앱에서 비식별 오류 코드 `EMBEDDING-MODEL-001`을 확인했고, DB 읽기 결과 `embedding_models` 전체/활성 건수가 `0/0`이었다. 업로드가 후속 인덱싱 Job의 모델을 고정하려고 유일한 활성 모델을 요구하는 [조회 코드](https://github.com/DocGrid/docgrid/blob/4ee00fe470510866c6fef6c711d11f644a4c69a7/backend/src/main/java/com/opensource/docgrid/domain/embedding/service/query/EmbeddingModelQueryService.java)이므로 GCS 장애로 분류하지 않았다.

저장소의 [BGE-M3 seed](https://github.com/DocGrid/docgrid/blob/4ee00fe470510866c6fef6c711d11f644a4c69a7/backend/src/main/resources/db/seed/R__seed_bge_m3_embedding_model.sql)를 시험 DB에 적용했다. 첫 수동 INSERT는 셸 SQL 인용 오류로 실행되지 않았고, 파일 원본 적용은 `UPDATE 0 / INSERT 1`이었다. 이후 전체/활성 모델은 `1/1`이다. 이는 **모델 메타데이터** 설정이지 실제 BGE-M3 서버 실행이나 임베딩 완료를 뜻하지 않는다.

```text
그림 3 — 실패 진단과 재시험 경계

18:07 첫 실행: 회원가입·로그인·A/B JWT 확인 성공
    → PDF 업로드 HTTP 500
    → 앱 오류 코드: 활성 임베딩 모델 없음
    → DB 조회: 모델 전체 0, 활성 0

기존 BGE-M3 seed 적용: UPDATE 0 / INSERT 1
    → DB 조회: 모델 전체 1, 활성 1

재시험: A 업로드 201 / B 업로드 201 / LB 업로드 201
    → 각 실행에서 A·B·LB 다운로드 3회 및 원본 SHA 일치

중간 재시도 1회는 명령 출력 캡처가 끊겨 결과를 판정하지 않고
별도 로그에 미판정으로 남겼다.
```

## 실행·결과 원장

아래 명령의 VM 이름·주소·키 경로는 `<...>`로 치환했다. 정확한 실행 인자는 로컬에서만 조립했고 시크릿은 명령행·로그에 넣지 않았다. 각 실행의 비식별 원본 요약은 `docs/test-results/evidence/issue-387/`의 개별 로그에 있다.

| 실행 ID·목적 | 위치·명령/방법 | 관측 결과 요약 | 해석 |
| --- | --- | --- | --- |
| `build-20261002-r1`, 배포 JAR | 로컬, `./backend/gradlew -p backend bootJar --offline --no-daemon` | BUILD SUCCESSFUL, JAR 1개 | 기준 커밋 `4ee00fe` 빌드 |
| `gcs-unit-20261002-r1/r3`, 회귀 | 로컬, `./backend/gradlew -p backend test --tests '*GcsStorageServiceTest' --tests '*FileStoragePropertiesTest' --tests '*DocumentUploadServiceTest' --offline --no-daemon --rerun-tasks` | r3 JUnit 14건 실행·통과, 실패 0 | GCS 어댑터·설정·업로드 단위 범위만 통과. 중간 r2는 캐시 `UP-TO-DATE`라 증거에서 제외 |
| `deploy-20261002-r1`, 동일 JAR | GCP APP-A/B, 암호화된 IAP SCP와 SHA-256 대조 | A/B 모두 `60c9359d6a1e54ecea1b38c923cff20fb49e1611c8508c13ad9fb5f2b9f75d04` | 두 VM이 같은 빌드 실행 |
| `app-a-start-20261002-r1` / `app-b-start-20261002-r1` | GCP 각 VM, systemd 시작 및 readiness 폴링 | A 13번째, B 15번째 폴링에서 HTTP 200; 두 서비스 active | 실제 앱 실행 및 DB 준비 상태 확인 |
| `gcp387-pdf-a-r1`, 최초 A 업로드 | GCP 내부 시험 VM, `verify_gcp_app_gcs_baseline.py --upload-via A` | 앞 단계 성공, 업로드 HTTP 500 | 활성 모델 0건을 발견; GCS 실패로 오인하지 않음 |
| `model-seed-20261002-r1`, 전제 복구 | GCP OpenSQL primary, 기존 seed SQL 원본 적용 | 최초 인용 실패 1회, 재실행 `INSERT 1`, 활성 `1/1` | 시험 DB 설정을 코드 전제에 맞춤 |
| `gcp387-pdf-a-r2`, 재시도 | GCP 내부 시험 VM | 출력·종료코드 캡처 누락 | 미판정; 성공 표본에 미포함 |
| `gcp387-pdf-a-r3`, A 업로드 | GCP 내부 시험 VM, HTTP 하네스 | 업로드 201, 다운로드 200×3, 해시 3/3; 626바이트 | A→GCS·DB 저장 후 양쪽 앱에서 읽기 성공 |
| `gcp387-pdf-b-r1`, B 업로드 | GCP 내부 시험 VM, 동일 하네스 | 업로드 201, 다운로드 200×3, 해시 3/3; 626바이트 | B→GCS·DB 저장 후 양쪽 앱에서 읽기 성공 |
| `gcp387-pdf-lb-r1`, LB 업로드 | GCP 내부 시험 VM, 동일 하네스 | 업로드 201, 다운로드 200×3, 해시 3/3; 627바이트 | LB 경유 정상 경로 성공; 어느 앱이 받았는지는 별도 계측 안 함 |
| `gcp387-db-gcs-r1/r2`, 저장 원장 대조 | 로컬에서 OpenSQL primary 읽기 + GCS 객체 바이트 직접 읽기, `verify_gcp_app_gcs_objects.py` | r2 DB 3행/GCS 3객체/해시 3일치, 모두 `UPLOADED` | HTTP 응답뿐 아니라 실제 객체와 DB 저장 위치 일치. 공개 스크립트는 비공개 자격증명 파일 경로를 실행 인자로 받도록 변경한 뒤 r2 재검증 |
| `gcp387-connectivity-r1`, 연결·상태 | GCP A/B, Redis `AUTH+PING`; 설정 키 수·Actuator·LB health 확인 | A/B Redis 2/2, 두 프록시 주소 2/2, 앱 health UP 2/2, LB HEALTHY 2/2, `.env` mode 0600 | 공용 Redis·설정·기동의 정상 상태 확인 |
| `gcp387-iam-r1`, 권한·동일성 | GCP VM scope, 버킷 IAM, JAR SHA 읽기 | A/B 동일 계정, cloud-platform scope 2/2, 버킷 objectUser 바인딩 1개, JAR 해시 동일 | JSON 키 없는 VM 신원으로 GCS 접근 |

`gcp387-pdf-a-r3`의 업로드 응답은 306.3ms, `gcp387-pdf-b-r1`은 939.7ms, `gcp387-pdf-lb-r1`은 345.8ms였다. 이는 각 1건의 기능 시험 값이므로 p95나 처리량을 주장하지 않는다.

```text
그림 4 — DB와 GCS의 별도 무결성 판정

실행별 원본 PDF SHA
  ├─ A 업로드 파일: 9ac6e6cd770c… / 626B
  ├─ B 업로드 파일: b9eee814187d… / 626B
  └─ LB 업로드 파일: 843428c336d0… / 627B
          │
          ├─ OpenSQL file_objects: 각 hash에 정확히 1행,
          │   storage_provider=GCS, document/version status=UPLOADED
          │
          └─ DB의 비공개 bucket/key로 GCS 객체 직접 읽기:
              3개 객체의 실제 바이트 수·SHA가 각각 원본과 일치

판정: HTTP 다운로드 9/9 일치 + DB↔GCS 직접 비교 3/3 일치.
```

## 남은 범위와 정리 상태

- 이번 PDF는 하네스가 생성한 유효한 1쪽 합성 PDF이다. 실제 사용자 PDF·DOCX, BGE-M3 임베딩, 청크·검색, Worker 재시도는 검증하지 않았다. Worker를 끈 상태이므로 문서/버전 상태 `UPLOADED`가 예상 결과다.
- A/B 설정에는 두 OpenProxy 주소가 있고 실제 DB 기록도 성공했지만, 프록시 A/B별 연결 분산 비율이나 장애 전환은 이 실행에서 측정하지 않았다. Flyway는 별도 DB primary 탐색 경로다.
- Redis는 A/B 각각 비밀번호 인증 `PING`을 통과했지만, Redis 장애·재시작·Pub/Sub 메시지 유실은 다루지 않았다.
- 시험 계정·문서·GCS 객체와 활성 모델 메타데이터 1건은 후속 검증을 위해 남겨 두었다. 로컬 임시 시크릿 사본과 SCP 대기 파일은 제거했고, 앱 A/B의 `/opt/docgrid/.env`만 요청에 따라 소유자 전용 `0600`으로 유지한다. A/B는 `systemd active/enabled`, LB는 2/2 HEALTHY다. GCP VM·LB·디스크의 과금은 계속된다.
- 미판정 재시도 1회와 첫 업로드 실패를 성공 건수에 섞지 않았다. 이번 결과는 **정상 경로 기준선**이며, 다음 OpenProxy·primary 장애 시험 전에 이 상태를 다시 확인해야 한다.
