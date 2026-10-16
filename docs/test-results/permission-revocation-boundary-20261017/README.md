# 문서 단건 권한 회수의 커밋·인가 경계 재현

기준 코드: `ed5d6019fd677bf1585249310bf5b6a721ff270a`. 공개 서비스·OpenSQL·OpenProxy가 아닌 **로컬 PostgreSQL 17.8**에서 실행했습니다.

## 실행과 결과

| 실행 | 목적 | 결과 | 해석 |
| --- | --- | --- | --- |
| [fixture 준비](preflight-failures.log) | 물리 복제 준비 | readiness·권한 오류 후 복구, streaming 2대 | 제품 결과에 포함하지 않음 |
| [baseline-run01](baseline-run01.log) | 경계 시험 | 3/3 실패: vector 확장 누락 | fixture에 확장 추가 |
| [baseline-run02](baseline-run02.log) | 재실행 | 3/3 실패: 시험 Context 감사 설정 누락 | EnableJpaAuditing 추가 |
| [baseline-run03](baseline-run03.log) | 실제 경계 관측 | 3/3 통과 | 아래 세 결함·제약을 실제 관측 |

- **COMMIT 대기 취소:** `pg_stat_activity.wait_event=SyncRep` 확인 후 `pg_cancel_backend`. Hikari 반환 전 COMMIT SQLWarning `01000` 1건을 관측했지만 MVC 응답은 **204**, Primary의 권한 행은 **0건**입니다. 원격 내구성 확정과 로컬 커밋 성공을 구분하지 못합니다.
- **오래된 캐시:** 정상 회수 뒤 시험 코드가 해당 캐시의 무효화만 되돌렸습니다. 권한 원장 0건인데 실제 `PermissionQueryService.canReadDocument`는 **true**입니다. 자연 발생률을 측정한 것이 아니라 원장·캐시 불일치 주입 시험입니다.
- **응답 유실 모델:** 첫 회수 응답을 버렸다고 가정하고 같은 대상 요청을 다시 실행하면 **404**입니다. 실제 네트워크 단절 시험은 아닙니다. 같은 요청으로 동기 확정하는 API 계약은 없습니다.

## 재실행

```bash
python3 scripts/opensql/permission_isolated_fixture.py up
./backend/gradlew -p backend permissionDurabilityTest --console=plain
python3 scripts/opensql/permission_isolated_fixture.py down
```

전용 루프백 포트와 내부 Docker 네트워크를 사용합니다. 이 시험은 설정 변경 권한이 있는 합성 PostgreSQL만 대상으로 고정되어 있습니다. COMMIT 시험은 존재하지 않는 동기 후보를 지정해 대기를 강제하며 finally에서 원복합니다. 이 경계 재현에 Patroni strict 설정이 있다고 주장하지 않습니다. fixture는 후속 회귀 시험에 재사용 중이며 마지막 시험 후 제거합니다.

## 한계

MockMvc는 실제 PermissionController·Service·JPA·Hikari를 실행하지만 공개 HTTP 인증·OpenProxy·HA 승격·모델 호출은 포함하지 않습니다. 상세·본문·다운로드·검색 전체 응답 및 권한 부활은 이 재현에서 실행하지 않았습니다. 이 파일은 수정 전 관측값이며 후속 수정 시험과 구분해 보존합니다.
