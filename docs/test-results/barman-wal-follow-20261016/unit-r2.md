# DR-BARMAN-UNIT-20261016-R2 — 로컬 안전 조건 시험

- 시작: 2026-10-16 20:58:32 KST
- 위치: 로컬 분리 작업 트리
- 목적: 역할 정족수·전환 순서·실패 중단을 검증
- 통과 기준: 단위 시험·Python 컴파일·diff 공백 검사 모두 종료 코드 0
- 종료: 2026-10-16 20:58:33 KST
- 앞선 R1은 시험 실행 뒤 파일 쓰기 권한 오류로 로그 캡처에 실패하여 판정하지 않았다.

| 절차 | 종료 코드 | 결과 |
| --- | ---: | --- |
| `python3 -m unittest discover -s scripts/opensql -p test_barman_wal_follow_primary.py -v` | 0 | 통과 |
| `python3 -m py_compile scripts/opensql/barman_wal_follow_primary.py scripts/opensql/test_barman_wal_follow_primary.py` | 0 | 통과 |
| `git diff --check` | 0 | 통과 |

## 원본 출력

```text
$ python3 -m unittest discover -s scripts/opensql -p test_barman_wal_follow_primary.py -v

test_aligned_receiver_does_not_stop_or_switch (test_barman_wal_follow_primary.BarmanWalFollowPrimaryTest) ... ok
test_config_validation_rejects_non_private_host (test_barman_wal_follow_primary.BarmanWalFollowPrimaryTest) ... ok
test_failed_switch_never_restarts_receiver (test_barman_wal_follow_primary.BarmanWalFollowPrimaryTest) ... ok
test_no_quorum_rejects_election (test_barman_wal_follow_primary.BarmanWalFollowPrimaryTest) ... ok
test_quorum_with_one_unreachable_node_elects_primary (test_barman_wal_follow_primary.BarmanWalFollowPrimaryTest) ... ok
test_switch_stops_receiver_then_verifies_new_target (test_barman_wal_follow_primary.BarmanWalFollowPrimaryTest) ... ok
test_two_primaries_reject_election (test_barman_wal_follow_primary.BarmanWalFollowPrimaryTest) ... ok

----------------------------------------------------------------------
Ran 7 tests in 0.002s

OK
$ python3 -m py_compile scripts/opensql/barman_wal_follow_primary.py scripts/opensql/test_barman_wal_follow_primary.py


$ git diff --check


```
