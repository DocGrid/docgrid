# 권한 회수의 동기 확정·재주장 검증

로컬 PostgreSQL 17.8·물리 Standby 2대에서 실제 Controller·JPA·Hikari를 실행했습니다. 공개·OpenSQL·OpenProxy 결과가 아닙니다. PG의 동기 후보 이름을 고정해 strict의 대기 경계를 모델링했으며 Patroni strict 운영을 검증했다고 주장하지 않습니다.

| 실행 | 결과 | 설명 |
| --- | --- | --- |
| [sync-run01](sync-run01.log) | 7개 중 5개 실패 | Controller가 새 Facade에 연결되지 않은 구현 오류 수정 |
| [sync-run02](sync-run02.log) | 7개 실패 | TestConfiguration의 Boot 기본 앱 탐색 문제 수정 |
| [sync-run03](sync-run03.log) | 7개 통과 | 기본 동기 확정·재주장 관측 |
| [sync-run04](sync-run04.log) | 8개 중 1개 실패 | statement_timeout만으로 SyncRep COMMIT 대기가 종료되지 않음 |
| [sync-run05](sync-run05.log) | **8개 통과** | COMMIT 네트워크 제한 추가 후 아래 결과 |

- COMMIT 취소 SQLWarning `01000` 1건 → **503**, 로컬 권한 행 0건. 같은 키 재시도 → **204**, 요청 원장 1건·WAL assertion 2회.
- 응답 유실 모델의 같은 키 재시도 → **204**, assertion 2회. 실제 네트워크 응답 차단 시험과 구분합니다.
- 동일 키 동시 요청 2개 → **204 2개**, 권한 0건·원장 1건·assertion 2회.
- 다른 대상에 같은 키 → **409**, 새 대상 권한 보존. 비인가 → **403**, 요청 INSERT 롤백.
- 동기 후보 설정 없음 → **503**, 권한 1건·요청 원장 0건.
- 후보 전체 부재 COMMIT → **5,074 ms** 후 결과 불명 **503**. statement_timeout의 실패 실행을 숨기지 않았습니다. 네트워크 제한은 로컬 롤백 보장이 아닙니다.
- 풀의 기본 `synchronous_commit=local`을 시험용으로 설정해도 회수는 SyncRep에서 대기했습니다. 회수 후 풀 설정은 **local**로 유지돼 SET LOCAL이 일반 업무에 누출되지 않았습니다. 운영 기본값을 local로 바꾼 것이 아닙니다.
- 오래된 USER 캐시의 최종 인가 허용은 이 단계에서 여전히 재현됩니다. 별도 수정 대상입니다.

## 운영 전제와 한계

`permission.revocation.synchronous-enabled` 기본값 false. true는 strict 동기 클러스터를 먼저 확인한 환경에서만 사용합니다. SQL의 동기 후보 존재 확인만으로 Patroni non-strict fallback을 검출하거나 안전하게 만든다고 주장하지 않습니다. 마이그레이션 V52와 애플리케이션 변경을 공개 서비스에 배포하지 않았습니다.

시험 fixture는 후속 최신 원장 인가 시험에 재사용합니다. 실제 모델·GCS·공개 브라우저 경로는 포함하지 않습니다. 동기 회수의 COMMIT 경고는 전용 TransactionManager에서 물리 연결이 풀로 반환되기 전에 판정합니다.
