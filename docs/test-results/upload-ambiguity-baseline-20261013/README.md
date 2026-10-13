# 최초 업로드 결과 불명 로컬 기준선

2026-10-13 KST, 기준 리비전 `71cb3687ee2bca178f7b1f7ccc8d1393d1e428b8`에서 시험 전용 코드만 추가해 실행했다. 저장소의 기존 업로드 동작을 수정하지 않았다.

## 시험 범위와 판정

| 시나리오 | 결정적 주입 지점 | 관측 결과 | 해석 |
| --- | --- | --- | --- |
| DB 커밋 후 응답 유실 | MockMvc가 서버 처리를 완료한 뒤 첫 HTTP 응답을 클라이언트에서 폐기 | 첫 요청 후 문서·버전·Job·FileObject 1/1/1/1; 재전송 후 2/2/2/1 | 파일 중복 제거는 요청 멱등성을 제공하지 않음 |
| 저장소 기록 후 중단 | 시험용 GCS 저장소 Port가 후보를 반환한 뒤, DB 서비스 진입 전에 `Error`로 실행 중단 | 저장 후보 1, 삭제 0; 문서·버전·Job·FileObject 0/0/0/0 | 현재 보상 삭제 경로가 실행되지 않는 경계에 후보가 남음 |
| 동일 HTTP 요청 동시 전송 | 저장소 후보 생성에 barrier를 두고 두 요청을 같은 사용자·파일·메타데이터로 전송 | 201 응답 2건; 문서·버전·Job·FileObject 2/2/2/1; 후보 저장 2·미사용 후보 삭제 1 | 동시 요청도 논리 업로드 두 건으로 처리됨 |

**범위 제한:** 첫째는 실제 네트워크 패킷 손실이 아니라 응답 폐기다. 둘째는 실제 GCS 객체나 JVM 프로세스 종료가 아니라 저장소 Port와 중단 경계의 모의다. 셋째는 로컬 PostgreSQL 17·pgvector 0.8.1에서 실행했다. OpenProxy·OpenSQL·GCS 운영 경로, Worker 인덱싱 완료, 원본 SHA-256 대조는 이 결과로 입증되지 않는다. 실제 클라우드 장애 시험은 별도 실행이 필요하다.

## 실행별 증거

| 실행 ID | 목적 | 결과 | 개별 로그 |
| --- | --- | --- | --- |
| `20261013-response-lost-01` | 응답 유실 첫 시도 | 환경 설정 실패, 시나리오 미실행 | [로그](run-response-lost-01.md) |
| `20261013-response-lost-02` | 응답 유실 재실행 | 통과 1/1 | [로그](run-response-lost-02.md), [원장](ledgers/20261013-response-lost-02-response-lost.csv) |
| `20261013-storage-stop-01` | 저장 직후 중단 | 통과 1/1 | [로그](run-storage-stop-01.md), [원장](ledgers/20261013-storage-stop-01-storage-before-db-stop.csv) |
| `20261013-concurrent-01` | 동일 요청 동시 전송 | 통과 1/1 | [로그](run-concurrent-01.md), [원장](ledgers/20261013-concurrent-01-concurrent-retry.csv) |
| `20261013-suite-01` | 세 시험 연속 실행 간 간섭 확인 | 통과 3/3 | [로그](run-suite-01.md), [원장 3개](ledgers/) |
| `20261013-suite-02` | 시험 사용자 선택 변경 후 재검증 | 통과 3/3 | [로그](run-suite-02.md), [원장 3개](ledgers/) |

CSV는 시험이 진행되는 동안 요청별로 작성됐다. 서버 상태와 클라이언트가 실제로 본 결과를 별도 열로 기록하며, 사용자 정보·버킷·객체 키·인증 값은 포함하지 않는다. Markdown 로그는 실행 출력과 CSV의 **사후 요약**이고, 전체 Gradle 콘솔 출력의 보존본은 아니다.

## 재현 절차

1. 운영 환경과 분리된 PostgreSQL 17에 `vector` 확장을 만들고, `application-test.yml`의 로컬 DB 설정을 사용한다. 이번에는 `pgvector/pgvector:0.8.1-pg17`을 localhost에만 바인딩한 임시 컨테이너를 썼다.
2. 실행마다 `UPLOAD_BASELINE_RUN_ID`를 새 값으로 지정한다. 같은 ID의 CSV가 이미 있으면 덮어쓰지 않고 실패한다.
3. 아래 명령의 테스트 필터를 목적별 메서드 또는 전체 클래스로 바꿔 실행한다. `JWT_SECRET`은 실행 시 메모리에서만 생성한다.

```bash
JWT_SECRET="$(openssl rand -hex 32)" \
UPLOAD_BASELINE_RUN_ID=YYYYMMDD-scenario-01 \
./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.document.integration.UploadAmbiguityBaselineIntegrationTest' \
  --offline --console=plain
```

4. 원장은 `backend/build/test-results/upload-ambiguity-baseline/`에 생성된다. 종료 후 시험 문서가 0건인지 확인하고 임시 DB를 제거한다. 이 실행에서는 최종 확인 결과 0건이었다.

후속 API 목표 계약은 [`docs/design/initial-upload-retry-contract.md`](../../design/initial-upload-retry-contract.md)에 분리했다. 이 기준선의 중복과 고아 후보는 **현재 동작의 재현 결과**이지 수정 완료 결과가 아니다.
