# OpenSQL·GCS 시점 복원 준비 상태 점검

## 목적과 판정

2026-10-15 KST, `develop`의 `e1f0de0b143e89fcc2ab06bfe8d939b3bc96dd77`을 기준으로 공개 서비스의 **읽기 전용** 복원 준비 상태를 확인했다. 이 점검은 백업이나 복원을 실행한 시험이 아니다.

**판정: NO-GO — 현재 상태로는 OpenSQL PITR과 DB·GCS 통합 복원 시험을 시작할 수 없다.** 실제 Primary의 `archive_mode`는 `on`이지만 `archive_command`가 실질적으로 비활성이고 `pg_stat_archiver.archived_count=0`이다. DB 3노드에 READY 디스크 스냅샷은 있지만 최신본이 2026-10-10 KST이고 디스크에 자동 스냅샷 일정이 연결되지 않았다. GCS 원본 버킷에는 7일 soft delete가 있으나, 별도 보존본은 이번 점검에서 확인하지 못했다.

| 확인 항목 | 실행 위치·방법 | 관측 | 복원 시험에 대한 해석 |
|---|---|---|---|
| DB 3노드 디스크 | 로컬 GCP CLI에서 인스턴스·스냅샷·정책 목록 읽기 | READY 스냅샷 5·3·3개, 최신본 2026-10-10 12:55~12:58 KST, 자동 일정 연결 0/3 | 디스크 복구 후보는 존재하지만 최신 시점 복원 근거가 아니다. 실행 중인 DB의 물리 백업 일관성도 이 조회로 확인하지 못했다. |
| WAL 보관 | 30분 만료 OS Login 키로 3노드 PostgreSQL 설정·`pg_stat_archiver` 읽기 | Primary 1·Standby 2, 모두 `archive_mode=on`, 아카이브 명령 비활성, 보관 수 0 | 연속 WAL 보관이 확인되지 않아 PITR 불가. |
| GCS 원본 버킷 | 로컬 GCP CLI 버킷 설정 읽기 | soft delete 604,800초(7일), Object Versioning 비활성 | 삭제 사고의 단기 보호 설정이다. 별도 버킷·계정의 독립 백업 또는 시점별 원본 보존을 입증하지 않는다. |
| 격리 대상 | 이번 실행에서 생성·조회하지 않음 | 미확인 | 운영 DB·버킷을 복원 대상으로 사용하지 않는다. |

## 실행별 기록

각 실행의 원시 응답에는 인스턴스·버킷·계정 식별자가 포함될 수 있어 저장하지 않았다. 명령은 JSON을 메모리에서 파싱해 **개수·상태·시각만** 출력했다. 실행 중 시각을 별도로 찍지 못한 회차는 아래 파일에 사후 기록임을 명시했다.

| 실행 ID | 목적 | 결과 | 기록 |
|---|---|---|---|
| `DR-PREFLIGHT-20261015-R1` | GCP 인스턴스·스냅샷·버킷 개수 | 조회 성공. DB 대상 분류 전 전체 집계 | [R1](runs/inventory-r1.md) |
| `DR-PREFLIGHT-20261015-R2` | 이름 패턴으로 DB 대상 추정·버킷 보존 설정 | 이름 패턴이 DB 3노드를 놓쳐 분류 무효. 버킷 soft delete 확인 | [R2](runs/inventory-r2.md) |
| `DR-PREFLIGHT-20261015-R3` | DB 3노드 디스크와 READY 스냅샷 대조 | 각 5·3·3개, 최신본 10월 10일 | [R3](runs/inventory-r3.md) |
| `DR-PREFLIGHT-20261015-R4` | 자동 스냅샷 정책 연결·별도 백업 버킷 후보 | DB 디스크 정책 연결 0/3; 이름상 백업 버킷 후보 0 | [R4](runs/inventory-r4.md) |
| `DR-WAL-PREFLIGHT-20261015-R1` | 첫 DB 노드의 WAL 설정 | 아카이브 명령 비활성; 당시 역할은 수집하지 않음 | [WAL R1](runs/wal-r1.md) |
| `DR-WAL-PREFLIGHT-20261015-R2` | Primary를 포함한 3노드의 WAL 설정 | Primary·Standby 모두 아카이브 명령 비활성, 보관 수 0 | [WAL R2](runs/wal-r2.md) |
| `DR-REPORT-QA-20261015-R1` | 제출 문서의 링크·공백·민감 식별자 점검 | 8개 파일, 상대 링크 11개, 발견 문제 0개 | [문서 QA](runs/report-qa-r1.md) |

## 다음 관문

1. OpenSQL 버전·Patroni 설정과 호환되는 **기준 백업 및 연속 WAL 보관 방식**을 선정하고, 백업을 운영 DB와 분리된 위치에 보관한다. 백업 파일 목록만으로 통과 판정하지 않고 격리 DB에서 기준 백업과 WAL을 실제 복원한다.
2. GCS 원본의 **독립 보존본**과 `provider/bucket/objectKey/size/SHA-256` 원장을 준비한다. soft delete 7일은 장기 백업으로 간주하지 않는다. 원본 복원 시 새 generation이 생길 수 있으므로 복원 후 실제 바이트를 재검증한다.
3. 격리 환경에서 DB를 지정 시점으로 복원하고, DB가 참조하는 원본의 누락·해시 불일치가 0건일 때만 백엔드·Worker를 시작한다. 이후 다운로드·검색·새 업로드 및 실제 RPO/RTO를 측정한다.

이번 점검으로 WAL 설정이나 GCS 보존 정책을 변경하지 않았고, 새 백업·복원 자원도 만들지 않았다. 두 회차의 임시 OS Login 공개키와 로컬 개인키는 각 조회 직후 삭제했다.
