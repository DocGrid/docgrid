# 새 버전 업로드 GCS·DB 경계 중단: 수정 전 재현

## 범위

기준 커밋 `3644063388cd6fcf93eb38d503ca98250ce45444`의 새 버전 업로드를 대상으로 한다. 제품 코드를 수정하기 전에 GCS 후보 저장이 끝나고 DB 버전·Job 기록을 시작하기 전 격리 앱을 중단한다. 기존 공개 앱 A/B와 운영 DB·버킷은 중단하거나 변경하지 않는다.

## 사전 선언한 실행

| 실행 ID | 목적·위치 | 절차 | 판정 기준 |
|---|---|---|---|
| `VU-GCS-DB-CRASH-20261014-R1` | 격리 Spring Boot·PostgreSQL·Redis와 별도 GCS 시험 버킷에서 수정 전 상태 재현 | 고유 시험 파일·요청 키로 새 버전을 보내고 `storeCandidate()` 반환 뒤 `persist()` 호출 전에 JVM을 멈춘다. 중단 직후 요청 원장·DB 버전/Job·GCS 객체/generation을 대조한다. 5분 리스 만료 후 같은 파일·키로 재시도하고 다시 대조한다. | 중단 지점이 확인되어야 유효하다. 재시도 후 DB 버전·Job이 각 1건이고 미참조 GCS 후보가 남으면 결함 재현이다. 객체가 남지 않으면 원인을 재분석하며 결함 재현으로 기록하지 않는다. |

## 기록 원칙

- 실행별 원본 로그를 `runs/` 아래 별도 파일로 보존하고, 실패·재시도를 덮어쓰지 않는다.
- KST 시각, 기준 커밋, 바이트 수·SHA-256, DB 건수, GCS 객체 수·generation 일치 여부, 앱 종료·재기동, 자원 정리를 구분해 기록한다.
- 요청 키 원문, 인증 정보, 계정·프로젝트·버킷·객체 식별자, 내부 주소는 로그와 저장소에 쓰지 않는다. 외부 원장에는 식별자 대신 실행별 해시와 불리언 대조 결과를 남긴다.
- `INDEXED` 선행 문서가 실제 Worker 결과가 아닌 시험 Fixture라면 그 사실을 실행 기록에 명시한다.
- GCS 객체 삭제는 시험 버킷 안의 이번 실행 객체만 generation 조건으로 수행한다. 중단 후 증거 대조 전에는 삭제하지 않는다.

## 시험 환경과 Fixture

- 기준 리비전: `3644063388cd6fcf93eb38d503ca98250ce45444` (`develop`의 수정 전 구현).
- 실행 위치: 격리 Spring Boot JVM, 루프백 PostgreSQL 17/pgvector·Redis, 이번 실행에서 만든 전용 GCS 버킷. 공개 앱 A/B와 운영 DB·버킷은 사용하지 않았다.
- 표본: 합성 TXT v1 1건과 바이트가 다른 합성 TXT v2 1건, v2 요청 키 1개. v2 원본 크기는 73바이트다.
- Worker를 껐고, 새 버전 업로드의 선행 조건을 맞추려고 v1의 문서·버전·Job 상태를 격리 DB에서 직접 `INDEXED`·`COMPLETED`로 설정했다. 실제 인덱싱 완료를 뜻하지 않는다.
- JVM 디버거가 [`DocumentVersionUploadFacade.uploadClaimed()`의 `persist()` 직전 115행](https://github.com/DocGrid/docgrid/blob/3644063388cd6fcf93eb38d503ca98250ce45444/backend/src/main/java/com/opensource/docgrid/domain/document/service/DocumentVersionUploadFacade.java#L109-L119)에 도달한 것을 확인했다. 디버거 메시지의 관찰 범위는 [`debugger-observation.safe.md`](runs/debugger-observation.safe.md)에 기록했다.

## 실행 결과

모든 시각은 KST(`+09:00`)다. GCS 객체 수에는 선행 v1 원본도 포함된다. DB 버전·Job 수 역시 v1을 포함하므로, 최종 2건은 v1 1건과 v2 1건이다.

| 시각 | 단계 | HTTP/앱 결과 | GCS 객체 | DB FileObject | DB 버전 / Job | 미참조 GCS | 요청 원장 |
|---|---|---|---:|---:|---:|---:|---|
| 20:13:26 | v2 최초 요청 | 응답 대기 | — | — | — | — | — |
| 20:14:41 | GCS 저장 후 DB 저장 직전 정지 | 디버거 115행 | 2 | 1 | 1 / 1 | **1** | `IN_PROGRESS` |
| 20:15:10 | 격리 JVM `SIGKILL` | 클라이언트 `RemoteDisconnected` | 2 | 1 | 1 / 1 | **1** | `IN_PROGRESS` |
| 20:15:54 | 리스 만료 전 동일 키 | HTTP 409 | 2 | 1 | 1 / 1 | **1** | `IN_PROGRESS` |
| 20:18:40 | 약 5분 리스 만료 후 동일 키 | HTTP 201, `versionNo=2` | 3 | 2 | 2 / 2 | **1** | `COMPLETED` |
| 20:19:04 | 완료된 동일 키 재전송 | HTTP 201, `versionNo=2` | 3 | 2 | 2 / 2 | **1** | `COMPLETED` |

재시도 후 v2 버전과 v2 Job은 각각 정확히 1건이다. 그러나 v2 바이트와 SHA-256이 동일한 GCS 객체가 서로 다른 generation으로 **2개** 있었고, 하나만 DB `file_objects`에서 참조했다. 중단 전에 생긴 후보 generation이 최종 스냅샷에서도 미참조로 남았다. 따라서 이번 조건에서 **멱등한 DB 결과와 GCS 객체 정합성은 서로 다른 문제**임을 재현했다. 객체별 generation·크기·SHA-256·DB 참조 여부는 `runs/*.safe.json`에 있다. 버킷·객체 경로, 계정, 토큰, 요청 키는 기록하지 않았다.

## 증거와 준비 실패

| 파일 | 용도 |
|---|---|
| [`VU-GCS-DB-CRASH-20261014-R1.safe.log`](runs/VU-GCS-DB-CRASH-20261014-R1.safe.log) | 최초 요청, 중단, 409, 201, 재전송, 자원 정리의 시각순 원장 |
| [`paused_before_db.safe.json`](runs/paused_before_db.safe.json), [`after_crash_before_retry.safe.json`](runs/after_crash_before_retry.safe.json) | DB 저장 전·앱 중단 후 객체와 원장 비교 |
| [`after_crash_hash_verification.safe.json`](runs/after_crash_hash_verification.safe.json), [`after_prelease_409.safe.json`](runs/after_prelease_409.safe.json) | 객체 바이트 해시와 리스 중 재시도 결과 |
| [`after_lease_retry_success.safe.json`](runs/after_lease_retry_success.safe.json), [`after_completed_replay.safe.json`](runs/after_completed_replay.safe.json) | 복구·완료 후 재전송의 DB/GCS 최종 대조 |
| [`startup-preflight-r1.safe.log`](runs/startup-preflight-r1.safe.log) ~ [`startup-preflight-r4.safe.log`](runs/startup-preflight-r4.safe.log) | 격리 DB 시작 준비 3회 실패와 pgvector 확장 설치 후 준비 완료 |

첫 supervisor 실행에서는 로그 디렉터리가 없어 캡처 자체가 실패했다. 이후 분리된 준비 로그 3건은 격리 DB 연결/유형 초기화 오류로 앱 시작에 실패했고, `vector` 확장을 설치한 네 번째 준비 시도에서 HTTP 200에 도달했다. 이 실패를 본 시험 성공으로 세지 않았다.

## 정리와 한계

세 GCS 객체는 관찰한 각 generation과 일치할 때만 삭제했다. 첫 정리 명령은 `gcloud storage ls`의 가상 디렉터리 표시를 실제 객체로 오인해 버킷 삭제를 보류했다. `objects describe`로 실제 객체 0개를 다시 확인한 뒤 전용 버킷을 삭제하고 격리 앱·PostgreSQL·Redis를 종료했다. 정리 재시도와 최종 완료 시각은 원장에 남겼다.

이 실행은 **수정 전 결함 재현 1회**다. 새 버전 후보 추적·정리 기능은 변경하지 않았다. Primary/OpenProxy 장애, 동시 업로드, 실제 PDF·DOCX 인덱싱, Worker 재개, 공개 환경은 검증하지 않았다. 후속 수정에서는 저장 전 후보 기록과 안전한 재사용 또는 정리를 구현한 뒤 **동일 중단 지점**에서 미참조 객체 0개로 수렴하는지 별도 실행 ID로 비교해야 한다.
