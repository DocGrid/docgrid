# primary 역할 이전 전 백업·클러스터 게이트 실행 로그

- 실행 ID: `ha397-backup-gate-01`
- 시작: 2026-10-03 14:04:14 KST
- 환경: GCP DB VM 3대, PR #398 정리 도우미 배포 상태
- 목적: 계획된 Patroni 역할 이전 전에 현재 primary·replica, etcd 정족수, 디스크·etcd 복구 증거를 확인한다.
- 성공 기준: primary 1·streaming replica 2, etcd endpoint 3/3 healthy, 각 DB 디스크 READY snapshot, etcd 온라인 snapshot의 무결성 및 격리 복구 확인. 하나라도 미확인하면 역할 이전은 보류한다.
- 출력 제한: 프로젝트·디스크·VM 주소, etcd key/value, 백업 원문, 계정·비밀은 기록하지 않는다.

| 시각 (KST) | 확인 | 관측 결과 | 판정 |
| --- | --- | --- | --- |
| 14:04 이전 | 현재 DB 역할·복제 | PostgreSQL 로컬 역할 primary 1·replica 2, 두 replica의 WAL receiver streaming. `patronictl list --format json`: leader 1·streaming replica 2 | 통과 |
| 14:04 이전 | etcd endpoint | `etcdctl endpoint health --cluster --write-out=json`: 3/3 healthy | 통과 |
| 14:04 이전 | Compute Engine snapshot | 세 부착 디스크에 READY snapshot 각 1개, 최신 생성 후 약 1.2~1.3시간 | 존재 확인. 앱·etcd 복구 가능성의 단독 증거는 아님 |
| 14:04 이전 | 설치 도구 | 현재 설치된 etcd v3.6 계열에는 `etcdutl`이 없음. `etcdctl`의 snapshot status/restore도 없음 | 격리 복구 리허설 도구 준비 필요 |
| 14:04~14:06 | etcd 온라인 snapshot | 실행 중인 `etcdctl 3.6.5`로 root-only 영속 볼륨에 snapshot 저장. snapshot 파일 **1,515,552 byte**, mode **0600** | 생성 성공. 원문은 외부로 전송하지 않음 |
| 14:06~14:08 | 동일 버전 검증 도구 | etcd 공식 v3.6.5 Linux AMD64 archive와 공식 `SHA256SUMS`를 받아 SHA-256 일치 확인. `etcdutl 3.6.5`만 임시 전송 | 공급원·버전·체크섬 관문 통과 |
| 14:08~14:09 | 오프라인 상태·복원 | `etcdutl snapshot status --write-out=json`: revision **4343**, 키 **8개**, DB 크기 **1,515,520 byte**. `etcdutl snapshot restore --data-dir <격리 경로>` 종료 코드 **0**, 복구 디렉터리 생성 확인 | snapshot 무결성·격리 파일 복원 통과. 실제 3멤버 서비스 기동 복구는 시험하지 않음 |
| 14:09~14:10 | 임시 데이터 정리 | 격리 복원 디렉터리와 VM/컨테이너의 임시 `etcdutl` 복사본 삭제. 원본 snapshot은 mode 0600·1,515,552 byte로 유지 | 시험 부산물 정리 완료 |
| 14:11:04 | GCP 원격 보존 | 원본 etcd snapshot을 포함한 node1 디스크의 별도 Compute Engine snapshot 생성, 상태 **READY** | VM 디스크 밖의 복구 지점 확보. PostgreSQL 논리 백업이나 전체 3멤버 복구 증거는 아님 |
| 역할 이전·node1 수동 복구 후 | 정족수 재확인 | node2가 단일 leader, node1·node3 streaming replica. etcd endpoint health **3/3** | 복구 후 정상 구성 확인. snapshot에서 서비스를 복원한 것은 아님 |

최종 판정: **계획된 역할 이전 전 백업 관문은 통과**했다. etcd snapshot 파일의 온라인 생성·무결성·격리 파일 복원과 별도 디스크 보존까지 확인했다. 단, **전체 3멤버 etcd 클러스터의 snapshot 기반 복구 재기동, PostgreSQL 논리 백업, 성공 응답 데이터의 RPO는 검증하지 않았다.** 실제 역할 이전에서 기존 리더 자동 재합류가 실패한 결과는 [별도 실행 로그](10-live-planned-switchover.md)에 기록했다.
