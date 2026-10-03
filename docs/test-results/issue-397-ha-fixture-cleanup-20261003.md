# HA 시험 계정 정리 가드 변경과 검증 — 이슈 #397

기록 시각: 2026-10-03 14:26 KST. 최초 로컬 시험의 명령별 정확한 시작 시각은 수집하지 못했다. 이후 GCP 실환경 시험의 시각·범위는 실행별 로그에 기록했다. 프로젝트·내부 주소·계정·비밀값은 기록하지 않았다.

## 문제와 변경

기존 `permission_fixture.sh`는 node1의 로컬 PostgreSQL이 primary일 때만 모든 동작을 허용했다. node1이 replica가 되거나 VM이 중단되면 기존 단일 VM 타이머만으로 합성 ADMIN 계정 삭제를 보장할 수 없다. 이번 변경은 각 노드에 기존 정리 도우미를 설치하고, 세 개의 독립 타이머를 확인한 뒤에만 네 개의 실행별 합성 계정을 생성하도록 오퍼레이터 도우미를 추가한다. 삭제는 관측된 현재 primary에서만 한다.

```text
시험 전: node1 timer ──▶ node1이 primary일 때만 삭제
                           └─ node1 상실/역할 이동 → 정리 실패 가능

변경 설계: node1 timer ─┐
          node2 timer ─┼─▶ 각자 로컬 역할 확인 ─▶ 현재 primary만 삭제
          node3 timer ─┘           └─ replica/DB 불가 → 타이머 유지·재시도

prepare: 역할 1/2 확인 → 3/3 timer 무장 확인 → primary에서 4계정 생성
cleanup: 현재 primary 삭제 → 각 생존 replica에 삭제 복제 확인 → timer 해제
```

| 파일 | 변경 목적 |
| --- | --- |
| `scripts/opensql/permission_fixture.sh` | 역할 조회를 분리하고 replica의 타이머 무장·상태 확인을 허용. 생성·삭제는 현재 primary에서만 수행. 실환경에서 발견된 DB 조회 실패 시 `role`·`guard-status`의 거짓 성공 출력을 차단 |
| `scripts/opensql/ha_fixture_cleanup.py` | 세 VM의 역할·타이머를 함께 확인하고 생성·정리를 순서대로 실행. 한 VM이 불가하면 정리 완료를 선언하지 않음 |
| `scripts/opensql/test_ha_fixture_cleanup.py` | 세 타이머 미무장, node1 상실·node2 승격, 삭제 실패, 이중 primary 관측을 결정적으로 검사. DB 조회 실패 시 오류 전파도 검사 |

## 실행별 검증

| 실행 ID·목적 | 위치·명령 | 관측 수치·결과 요약 | 판정·기록 |
| --- | --- | --- | --- |
| `fixture-397-import-01` 테스트 호출 방식 확인 | 로컬, `python3 -m unittest scripts/opensql/test_ha_fixture_cleanup.py -v` | **1건 로드 오류**. `permission_replica_lag` 모듈을 찾지 못함. 제품 코드 실패가 아니라 이 호출 방식의 검색 경로 문제 | 실패 기록: [호출 방식](evidence/issue-397/01-local-import.md) |
| `fixture-397-unit-02` 새 정리 로직 | 로컬, `python3 -m unittest discover -s scripts/opensql -p 'test_ha_fixture_cleanup.py' -v` | **6/6 통과**, 실패 0. node1 상실 때 node2 삭제·접근 불가 타이머 미해제 검증 | 로컬 모형 통과: [정리 로직](evidence/issue-397/02-local-unit.md) |
| `fixture-397-unit-06` primary 미확인 차단 추가 | 로컬, 같은 `unittest discover` 명령 재실행 | **7/7 통과**, 실패 0. primary가 하나도 보이지 않으면 삭제·타이머 취소 0건 | 로컬 모형 통과: [추가 회귀](evidence/issue-397/06-local-no-primary.md) |
| `fixture-397-regression-03` 기존 권한 시험 | 로컬, `python3 -m unittest discover -s scripts/opensql -p 'test_permission_replica_lag.py' -v` | **6/6 통과**, 실패 0 | 로컬 회귀 통과: [기존 시험](evidence/issue-397/03-local-regression.md) |
| `fixture-397-syntax-04` 쉘·패치 검사 | 로컬, `bash -n scripts/opensql/permission_fixture.sh`, `git diff --check` | 두 명령 모두 종료 코드 **0** | 정적 검사 통과: [구문 검사](evidence/issue-397/04-local-syntax.md) |
| `fixture-397-gcp-05` 실제 게스트 접근 준비 | GCP IAP 경유 기존 키·저장된 호스트 키로 node1 읽기 전용 접속 | 대상 프로젝트·VM·등록 키·호스트 키 매칭 확인, SSH 공개키 인증 거부. VM 파일 변경 **0건**, 계정 생성 **0건** | 실환경 시험 미실행: [접속 관문](evidence/issue-397/05-gcp-readiness.md) |
| `b447ad2079bb` 수동 정리 연습 | GCP DB VM 3대, 세 노드 가드 무장 → primary에서 4계정 생성·삭제 → 가드 취소 | 배포 해시 3/3, 계정 4→0명, 타이머 3/3→0/3 | **통과**: [수동 연습](evidence/issue-397/08-live-manual-rehearsal.md) |
| `3c9a99125769` 자동 만료 | GCP DB VM 3대, 300초 타이머 3개 → 4계정 생성 → 수동 삭제 없이 관측 | 계정 4→0명, primary 타이머 service `success`, replica 재시도 기록, 최종 타이머 0/3 | **통과**: [자동 만료](evidence/issue-397/07-live-timer-expiry.md) |
| `ha397-backup-gate-01` 백업 관문 | etcd snapshot 생성·공식 체크섬 검증 도구로 상태 확인·격리 복원; GCP 디스크 snapshot 보존 | etcd revision 4343·키 8개, snapshot 파일 1,515,552 byte, 격리 복원 종료 코드 0, READY 디스크 snapshot | **부분 범위 통과**: [백업 관문](evidence/issue-397/09-live-backup-gate.md). 전체 3멤버 서비스 복원 시험은 아님 |
| `6d5b9c7345df` 계획 역할 이전 | GCP Patroni node1→node2 switchover, 새 primary에서 계정 삭제, 이전 리더 재합류 확인 | node2 승격·계정 4→0명·가드 0/3. 그러나 node1 `stopping` 지속해 **수동 컨테이너 재시작 필요** | **전체 성공 기준 미달**: [역할 이전](evidence/issue-397/10-live-planned-switchover.md) |
| `fixture-397-query-failure-11` 오류 전파 회귀 | 로컬 `unittest discover`, `bash -n`, `git diff --check` | 단위 시험 **8/8 통과**; 쉘·패치 검사 0 오류 | **통과**: [DB 실패 회귀](evidence/issue-397/11-local-query-failure-regression.md) |
| `fixture-397-deploy-12` 수정본 실환경 배포 | GCP DB VM 3대, 수정본 SHA-256 비교·역할/계정/타이머 재조회 | 해시 3/3 일치, primary 1·streaming replica 2, 계정 0명, 타이머 0/3, etcd healthy 3/3 | **정상 상태 배포 통과**: [수정본 배포](evidence/issue-397/12-live-query-failure-fix-deployment.md) |
| `fixture-397-access-13` 임시 접근 정리 | 인스턴스 전용 SSH 키 정확한 일치 확인 후 제거, 로컬 키·임시 검증 도구 삭제 | 임시 키 잔여 0/3, 프로젝트 공통 키 4개 유지, 로컬 임시 키 삭제 | **통과**: [접근 원복](evidence/issue-397/13-temporary-ssh-key-cleanup.md) |

## 발견한 실패와 현재 한계

- 실제 역할 이전에서 새 primary의 계정 정리는 성공했으나, **기존 리더가 `stopping`에 머물러 자동으로 replica에 재합류하지 못했다**. 현재는 해당 컨테이너만 수동 재시작하고 기존 bootstrap을 실행해 node2 primary·node1/3 streaming replica·etcd 3/3으로 복구했다. 원래 리더로 되돌리는 두 번째 역할 이전은 안전상 하지 않았다. Patroni 강등이 멈춘 근본 원인은 아직 확인되지 않았다.
- 당시 도우미는 DB 접속이 거부돼도 `role=replica` 또는 빈 `fixture_count=`를 종료 코드 0에 보고했다. 이는 뒤따르는 오퍼레이터의 상태 판정을 흐릴 수 있으므로 DB 조회 실패를 비정상 종료하도록 수정했다. 로컬 결정적 회귀는 통과했고, 수정본의 정상 상태 동작·해시는 GCP 3대에서 확인했다. 수정본을 시험하려고 실제 DB를 다시 중단하지는 않았다.
- Python 오퍼레이터 `ha_fixture_cleanup.py`의 `gcloud compute ssh` 실서버 호출 경로는 **이번에 실행하지 않았다**. 승인된 인스턴스 전용 임시 키와 고정 호스트 키를 사용하는 direct IAP SSH로 동일 단계만 수동 실행했다. 따라서 오퍼레이터 명령 자체의 실서버 E2E 통과를 주장하지 않는다.
- 오퍼레이터는 승인된 GCP 계정·프로젝트·zone·SSH 키를 `OPENSQL_EXPECTED_ACCOUNT`, `OPENSQL_EXPECTED_PROJECT`, `OPENSQL_GCP_ZONE`, `OPENSQL_SSH_KEY`로 제공받도록 설계됐다. `prepare <12자리 실행 ID>`는 세 타이머를 무장하고 네 계정을 만들며 `cleanup <같은 실행 ID>`는 새 primary에서 삭제하도록 설계됐다. 이번에는 이 명령의 수동 등가 절차만 실서버에서 사용했다.
- node1 상실처럼 한 VM이 돌아오지 않은 경우 계정은 생존 primary에서 삭제될 수 있으나, 사라진 VM의 타이머는 확인할 수 없다. 도우미는 이를 성공으로 숨기지 않고 재감사 필요 오류로 남긴다.
- etcd snapshot의 격리 파일 복원과 디스크 snapshot 존재는 확인했지만, 3멤버 DCS 전체 복구·PostgreSQL 논리 백업은 검증하지 않았다. HTTP 요청 원장이나 부하를 동반하지 않았으므로 HA RTO·RPO, 성공 응답 데이터 보존도 검증하지 않았다. 이전 리더의 재합류 멈춤 원인을 해결·재시험하기 전에는 더 큰 장애 주입을 진행하지 않는 것이 안전하다.
