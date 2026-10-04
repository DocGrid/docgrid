# OpenSQL 리더 VM 장애 시험의 독립 복구 가드

## 결론

기존 로컬 백그라운드·launchd 가드는 [비계획 리더 장애 시험](opensql-primary-unplanned-fault-load-20261004.md)에서 지연된 GCP API 호출에 실패했고, 운영자가 VM을 수동 재시작했다. 이 작업은 가드를 GCP Workflows로 옮겨 노트북·장애 대상 VM과 분리했다. 무해한 시험 VM에서 **120초 지연 시작 성공 1회**와 **지연 전 권한 철회에 따른 실패 1회**를 관측했다. 실패 실행은 로컬 장애 주입 게이트에서 `BLOCKED`가 된다. 실제 리더 VM을 이 가드로 재시작하는 시험은 아직 별도 Worker/HA 실행 범위다.

## 경계와 동작

```text
시험자 ── 실행 요청(delay만 입력) ──▶ GCP Workflows
  └─ 명령 종료 / 노트북 단절          │ 120~3600초 관리형 대기
                                    ▼
                    배포 시 고정된 zone·instance 상태 확인
                         ├─ RUNNING    → 아무 동작 없이 반환
                         ├─ TERMINATED → start → 45초 후 RUNNING 확인
                         └─ 오류/403/다른 상태 → FAILED

전용 서비스 계정: get/start 두 권한만 대상 VM 한 대에 조건부 부여.
다른 VM으로 대상을 바꾸려면 workflow 재배포와 해당 VM의 별도 IAM 바인딩이 필요.
```

[`ha_vm_recovery_guard.yaml`](../../scripts/opensql/ha_vm_recovery_guard.yaml)은 대상 VM을 사용자 입력으로 받지 않는다. [`ha_vm_guard_gate.py`](../../scripts/opensql/ha_vm_guard_gate.py)는 실제 실행의 `ACTIVE` 상태, workflow 일치, 남은 시간을 검사한다. **게이트 통과는 미래의 API 성공 보장이 아니다.** 따라서 GCP 내부에서 지연 실행이 성공한 실측, 만료 전 IAM 확인, 독립적인 수동 복구 절차와 최신 snapshot을 별도 선행 조건으로 둔다.

## 실행별 결과와 근거

| run ID | 위치·방법 | 실제 결과 | 판정·원본 요약 |
| --- | --- | --- | --- |
| `guard422-local-01` | 로컬 `python3 -m unittest -v test_ha_vm_guard_gate.py` | 6/6 통과 | [게이트 단위 로그](evidence/issue-422/guard422-local-01.md) |
| `guard422-regression-01` | 로컬 `python3 -m unittest discover -s scripts/opensql -p 'test_*.py'` | 96/96 통과 | [스크립트 회귀 로그](evidence/issue-422/guard422-regression-01.md) |
| `guard422-delayed-start-01` | GCP 시험 VM 정지, Workflows 120초 지연 실행 | VM `RUNNING`, 실행 `SUCCEEDED`, 시작 후 상태 확인 완료 | [지연 시작 로그](evidence/issue-422/guard422-delayed-start-01.md) |
| `guard422-permission-denied-01` | 같은 시험 VM 정지, 실행 예약 후 대상 IAM 철회 | 실행 `FAILED`/403, VM `TERMINATED`, 게이트 `BLOCKED`/종료 2 | [권한 상실 로그](evidence/issue-422/guard422-permission-denied-01.md) |

배포 전에 Workflows API 최초 활성화 직후 서비스 에이전트가 아직 없어 첫 배포가 실패했다. Google 관리 서비스 에이전트를 생성한 다음 같은 소스를 배포해 성공했다. CLI의 `--call-log-level=none` 인자도 이 SDK에서 오류를 내어 기본 로깅 설정으로 재시도했다. 이 두 준비 실패를 가드 자체의 지연 실행 결과와 혼동하지 않는다.

## 실제 장애 전 중단 조건

1. 가드의 대상·zone이 **현재** 리더 VM과 일치하고, 전용 계정의 조건부 get/start 권한이 만료 전인지 확인한다.
2. Workflows 실행이 `ACTIVE`이며 지연 기한이 최소 120초 이상 남았는지 `ha_vm_guard_gate.py`로 확인한다. 상태가 `FAILED`/`CANCELLED`거나 응답이 불완전하면 장애를 주입하지 않는다.
3. etcd 3/3, 리더 1·복제본 2, 최신 백업/snapshot, 앱 A/B와 별도의 수동 복구 경로를 재확인한다. 하나라도 불확실하면 중단한다.
4. 장애 후에는 가드 `SUCCEEDED`와 대상 VM `RUNNING`을 **둘 다** 확인한다. `ALREADY_RUNNING`은 리더 VM 상실·자동 복구의 증거가 아니다.
5. 시험 뒤 대기 실행을 취소하고 대상 IAM 바인딩을 제거한다. 이번 무해한 소형 시험 VM은 검증 후 삭제했다. 기존 DB·앱·캐시·부하 VM은 건드리지 않았다.

## 한계

- 권한 상실은 실제 GCP에서 검증했지만, Workflows의 네트워크 단절과 노트북 자체 전원 차단을 물리적으로 주입하지 않았다. 로컬 게이트의 `FAILED`·`CANCELLED` 차단 시험만 있다.
- Workflows가 시작 명령을 보낸 뒤 45초 내 VM `RUNNING`으로 바뀌지 않으면 실행은 `FAILED`가 된다. 이후 수동 점검이 필요하다.
- IAM 바인딩 만료나 서비스 장애로 지연 시각에 실패할 수 있다. 이 가드는 독립적인 **최후 복구 수단**이지 무조건적 자동 복구 보장이 아니다.
- 공개 문서는 GCP 프로젝트·계정·VM 실명·주소·원본 오류 payload를 제외한다.
