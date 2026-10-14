# 새 버전 업로드 GCS·DB 경계 중단: 수정 후 재시험

## 목적과 실행 조건

- 실행 ID: `VU-GCS-DB-RECOVERY-20261014-R1`
- 기준: [수정 전 재현 #495](https://github.com/DocGrid/docgrid/pull/495), [수정 #496](https://github.com/DocGrid/docgrid/pull/496)
- 실행 리비전: `203692492c8e765c67735e7fab200140fd4c68de` (`develop`에 #496 병합)
- 위치: 공개 서비스와 분리한 로컬 Spring Boot JVM·PostgreSQL 17/pgvector·Redis와 이번 실행만을 위한 GCS 버킷. 공개 앱 A/B, 운영 DB·버킷은 사용하지 않았다.
- 표본: 합성 TXT v1 1건(60바이트), 바이트가 다른 합성 TXT v2 1건(60바이트), v2 요청 키 1개. v2 HTTP 요청은 최초·리스 전 재시도·리스 후 재시도·완료 후 재전송의 4회다.
- Worker·동기화 Dispatcher와 후보 자동 삭제를 껐다. 새 버전의 선행 조건을 맞추기 위해 **v1의 `INDEXED` 상태를 격리 DB에서 직접 설정**했다. 실제 인덱싱 완료를 뜻하지 않는다.

**합격 기준:** GCS `store` 반환 직후이자 DB `persist` 직전의 중단을 확인한다. 같은 파일·키로 리스 만료 후 재시도하면 v2 버전·Job이 각 1건이고, 채택된 v2 원본의 SHA-256과 generation이 중단 전 후보와 같으며, 시험 범위의 미참조 GCS 객체가 0건이어야 한다. 완료 키 재전송에도 수가 늘지 않아야 한다.

## 절차와 판정

1. 병합 커밋을 `bootJar`로 빌드했다. 격리 앱의 HTTP 경로로 합성 계정을 가입·로그인하고 v1을 업로드한 뒤, 시험 DB에서 v1 상태만 `INDEXED`로 설정했다.
2. JVM 디버거에 `DocumentVersionUploadFacade.uploadClaimed()` **121행**, 즉 `recoverOrStoreCandidate()`가 반환되고 `persist()`를 호출하기 직전의 중단점을 설정했다. v2 첫 요청이 그 지점에 멈춘 것을 대화형 디버거 출력에서 직접 확인했다.
3. 멈춘 상태에서 DB 요청 원장과 GCS 객체·바이트 해시·generation을 대조했다. 격리 JVM의 PID와 시험용 JAR 경로를 확인한 후 그 JVM에만 `SIGKILL`을 보냈다. 첫 HTTP 요청은 응답을 받지 못했다.
4. 동일 격리 DB·버킷으로 앱을 재시작했다. 리스 만료 전 같은 키 요청은 HTTP 409였고, 약 5분 리스 만료 뒤 같은 키 요청은 HTTP 201·`versionNo=2`였다. 완료된 같은 키를 한 번 더 보내 같은 응답을 받았다.
5. 각 단계의 DB 건수, GCS 객체별 SHA-256 일치 여부, 참조 여부, generation 별칭을 대조했다. 버킷·객체명·프로젝트 ID·계정·토큰·요청 키 원문은 기록하지 않았다.

아래 시각은 KST(`+09:00`)의 **스냅샷 수집 시각**이다. JVM 중단과 HTTP 응답 각각의 정확한 시각은 별도로 수집하지 않아, 사건 시각을 인접 스냅샷 사이로만 한정한다. 객체 수와 DB 건수에는 선행 v1도 포함된다.

| 스냅샷 시각 | 단계 | HTTP 관찰 | GCS 객체 | DB 버전 / Job | 미참조 GCS | 요청 상태 |
|---|---|---|---:|---:|---:|---|
| 22:07:13 | DB 저장 직전 정지 | 첫 v2 응답 대기 | 2 | 1 / 1 | 1 | `IN_PROGRESS`, 후보 기록 있음 |
| 22:07:46 | 격리 JVM 종료 뒤 | 첫 요청 `RemoteDisconnected` | 2 | 1 / 1 | 1 | `IN_PROGRESS` |
| 22:08:31 | 리스 만료 전 재시도 뒤 | HTTP 409 | 2 | 1 / 1 | 1 | `IN_PROGRESS` |
| 22:11:12 | 리스 만료 후 재시도 뒤 | HTTP 201, v2 | **2** | **2 / 2** | **0** | `COMPLETED`, 후보 기록 제거 |
| 22:11:45 | 완료 키 재전송 뒤 | HTTP 201, 응답 동일 | **2** | **2 / 2** | **0** | `COMPLETED` |

중단 전 미참조 v2 후보는 60바이트이며 업로드 입력과 SHA-256이 일치했다. 후보 원장과 GCS generation도 일치했다. 복구 후 DB가 참조하는 v2 객체는 **중단 전 그 후보와 같은 generation 별칭**을 유지했으며 SHA-256도 같은 입력과 일치했다. 즉 재시도가 새 GCS 객체를 만들지 않고 기존 후보를 채택했다. 실행 후 자동 판정 6개(`candidate_at_boundary`, `same_generation_after_recovery`, `v2_adopted`, `one_new_version_and_job`, `no_gcs_orphan`, `replay_stable`)가 모두 참이었다.

## 수정 전과 비교

| 같은 장애 경계에서의 관찰 | #495 수정 전 | 이번 수정 후 |
|---|---:|---:|
| 리스 후 v2·Job | 각 1건 | 각 1건 |
| 최종 GCS 객체 수(v1 포함) | 3개 | **2개** |
| 최종 미참조 GCS 객체 | 1개 | **0개** |
| 중단 전 후보 generation 채택 | 아니요 | **예** |

두 실행은 같은 코드 경계와 요청 순서를 사용했지만 **합성 파일 바이트와 크기는 서로 다르다**(수정 전 v2 73바이트, 이번 v2 60바이트). 따라서 이 비교는 해당 장애 경계의 객체 정합성 검증이지, 처리량·지연시간의 우열 비교가 아니다. 이번 재시험은 **1회**이며 Primary/OpenProxy 장애, 동시 업로드, 실제 PDF·DOCX 인덱싱, Worker 재개, 공개 서비스, 24시간 경과 후보의 자동 삭제는 검증하지 않았다.

## 증거와 정리

- [실행 원장](runs/VU-GCS-DB-RECOVERY-20261014-R1.safe.log): 목적·절차·판정, 단계별 관찰, 합격 기준과 한계.
- [단계별 안전 스냅샷](runs/paused_before_db.safe.json): `paused_before_db`, `after_crash`, `after_prelease_retry`, `after_lease_retry`, `after_completed_replay` 다섯 파일. 객체·generation은 실행 내에서만 통하는 별칭으로 표시했다.
- [자원 정리 로그](runs/cleanup.safe.log): 22:12 KST에 시험 객체 2개를 관찰한 generation 조건으로 삭제했고, 실제 객체 0개를 확인한 뒤 전용 버킷과 격리 컨테이너를 제거했다. 임시 계정·토큰·DB 비밀번호를 담은 시험 상태 파일도 삭제했다.
- Compose 사전 준비 2회는 서비스 이름 및 고정 컨테이너·볼륨 이름 충돌로 실패했다. 기존 자원은 변경하지 않았고 고유 이름의 임시 컨테이너로 다시 준비했다. [첫 준비 기록](runs/setup-preflight-r1.safe.log), [두 번째 준비 기록](runs/setup-preflight-r2.safe.log).

원본 앱 출력은 민감한 저장소 위치가 섞일 가능성이 있어 저장하지 않았다. 디버거 중단 메시지는 실행 중 직접 관찰했지만 원본 세션 로그를 보존하지 않았다. 스냅샷은 DB·GCS 명령 결과에서 식별자를 제거한 뒤 기록한 값이며, 원본 응답 본문·버킷명·객체 키는 저장소에 포함하지 않았다.
