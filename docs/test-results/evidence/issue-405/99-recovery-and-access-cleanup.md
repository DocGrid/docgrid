# 역할 이전 시험 종료·접근 정리

- 확인 시각: 2026-10-03 23:18 KST경. 개별 원격 명령의 절대 시각은 동일 시계로 수집하지 않아 이 시각은 정리 완료 시점의 근사치다.
- 목적: 시험의 DB·앱·etcd 건강 상태와 단기 접근 권한·합성 계정 제거를 확인한다.

| 위치 | 비식별 명령·방법 | 관측 결과 | 해석 |
| --- | --- | --- | --- |
| OpenSQL 새 primary | `patronictl list --format json` | leader 1·streaming replica 2, timeline 8 | 계획 이전 후 단일 리더·자동 재합류 확인. |
| etcd | `etcdctl endpoint health --cluster --write-out=json` | 멤버 endpoint 3개 중 healthy 3개 | 정족수 유지. 이 시험에서 정족수 상실을 주입하지 않았다. |
| 내부 앱 LB | `gcloud compute backend-services get-health` | 백엔드 2개 중 HEALTHY 2개 | A/B가 헬스 체크에 다시 편입됨. 별도 장시간 soak 검증은 아님. |
| DB VM 3대 | `docgrid-permission-fixture remove`, 이어서 각 VM `cancel`·`guard-status` | 모든 실행의 합성 계정 0명, `armed=false fixture_count=0` 3/3 | 각 실행별 4명 제거·자동 정리 타이머 해제. |
| 부하 VM | 정확히 지정한 `.ha405-token*` 파일 5개 존재·mode 0600 확인 후 삭제 | 단기 JWT 잔여 0개 | 실행 원장의 요청 ID·숫자는 보존하지만 토큰은 보존하지 않는다. |
| GCP instance metadata | 원래 0개였던 4개 VM의 `ssh-keys` 항목이 이 시험 공개키 단일 항목인지 대조 후 `remove-metadata --keys=ssh-keys` | DB 3대·부하 VM 1대 모두 임시 키 항목 0개 | 프로젝트 공통 SSH 키·기존 다른 metadata는 변경하지 않았다. |
| 로컬 임시 디렉터리 | 정확히 지정한 개인키·공개키·known_hosts·JWT 5개만 삭제 | 디렉터리 잔여 0개 | 장기 비밀을 저장소·결과 로그에 복사하지 않았다. |

DB·앱·부하·캐시 VM은 이 정리에서 중지·삭제하지 않았다. 단기 자격증명만 제거했으므로 후속 실험에는 새 접근 수단과 별도의 사전 점검이 필요하다.
