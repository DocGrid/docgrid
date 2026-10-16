# Barman 기준 백업·WAL 외부 연속 보존

## 경계

기존 Barman은 백업 VM의 로컬 디스크에 기준 백업과 WAL을 보관한다. [리더 전환 후 수신 검증](../test-results/barman-wal-follow-20261016/README.md)은 백업 VM의 WAL 연속성을 확인했지만, 백업 VM 자체를 잃었을 때의 독립 사본은 제공하지 않는다.

이 기능은 Barman의 **완료된 전체 기준 백업**을 `barman export-backup`으로 내보내고, 로컬에 보관된 완성 WAL과 timeline history를 별도 비공개 GCS 버킷에 5분마다 동기화한다. Barman의 DB 연결, 백업 생성 시각, 로컬 보존 정책은 바꾸지 않는다.

```text
OpenSQL 3노드 → Barman 백업 VM
                   ├─ DONE 전체 백업 → export-backup tar
                   │                  → SHA-256 파일명 확인
                   │                  → GCS 객체 CRC32C 대조
                   │                  → complete.json 마지막 기록
                   └─ 완성 WAL·.history → GCS wals/ (5분마다 재시도)

백업 VM 상실 → 비공개 GCS에서 원래 이름의 tar와 WAL 다운로드
           → 새 Barman의 import-backup → 필요한 후속 WAL 배치 → PITR
```

## 저장소와 권한

DB 백업 전용 버킷을 문서 원본 백업·복원 버킷과 분리한다. 버킷은 uniform bucket-level access와 public access prevention을 사용하고, **이번 기능에 추가한** 백업 VM 서비스 계정의 객체 생성·조회 권한은 해당 버킷 범위로 제한한다. 기존 프로젝트 권한의 범위까지 이 문서에서 검증한 것은 아니다. 버킷명, 프로젝트 ID, 서비스 계정, 내부 주소는 저장소에 기록하지 않는다. Barman 작업은 VM 서비스 계정의 메타데이터 인증을 사용하는 `gsutil`로 전송하므로 장기 서비스 계정 키 파일이 필요 없다.

[Barman의 export/import 계약](https://docs.pgbarman.org/release/3.20.0/user_guide/export_import.html)에 따라 아카이브의 원래 파일명을 유지해야 `barman import-backup`이 내장 SHA-256을 확인할 수 있다. 전송기는 재시도 중 남은 파일도 SHA-256 파일명과 대조한 뒤에만 올린다. GCS 객체의 크기와 CRC32C가 로컬 아카이브와 일치할 때에만 `complete.json`을 마지막에 쓴다. 복원은 이 완료 표시가 있는 아카이브만 선택한다.

WAL은 완성된 24자리 세그먼트·`.backup` 및 8자리 timeline `.history`만 허용한다. [gsutil rsync의 `-x` 상대 경로 규칙](https://github.com/GoogleCloudPlatform/gsutil/blob/master/gslib/commands/rsync.py)에 맞춘 제외식을 적용하고 `-c`로 체크섬을 검사한다. 원격 객체를 삭제하는 `-d`는 사용하지 않아 Barman 로컬 보존 정책이 외부 사본을 지우지 않는다. GCS 전송 중단 시 서비스는 실패하고 마지막 성공 시각은 갱신되지 않는다. 다음 timer 실행이 같은 백업과 WAL을 다시 검사한다. 별도 health timer가 5분마다 `check`를 실행해 마지막 성공이 15분을 넘거나 최근 실행이 실패하면 비정상으로 반환한다. 두 systemd service의 실패 상태와 journal의 안전한 단계명을 운영 신호로 사용한다.

## 운영 관문

| 항목 | 확인 및 제한 |
| --- | --- |
| 기준 백업 | 적어도 하나의 `DONE` 전체 백업이 있어야 전체 동기화 성공으로 기록한다. 기준 백업 **생성 주기**는 이 기능의 범위 밖이며 별도 일정이 필요하다. |
| WAL 시점 | 미완성 `.partial`은 복원 가능한 완성 세그먼트가 아니어서 전송하지 않는다. 저부하 구간에서 세그먼트가 닫히지 않으면 외부 사본의 시점은 5분보다 오래될 수 있다. 강제 WAL 전환 또는 목표 RPO는 별도 결정·시험이 필요하다. |
| 보존 | 버킷에서 현재 객체를 자동 삭제하지 않는다. 기준 백업 생성 주기와 격리 복원 시험이 확정되기 전에는 생존한 유일한 기준점을 만료시키지 않는다. 비현재 객체 정리 정책은 버킷 설정에 별도로 기록한다. |
| 검증 | 로컬 단위 시험은 완료 표시·재시도 경계를 확인한다. 실제 GCS 전송과 새 VM에서의 `import-backup`·PITR는 각각 독립된 실행 기록으로 확인해야 한다. |
| 관측 | `barman-offsite-health.timer`가 주기적으로 마지막 성공 시각을 점검한다. `systemctl --failed`와 두 service의 상태를 확인한다. 상태 파일과 journal에는 단계명·건수·시각만 기록하며 CLI 원문 오류는 출력하지 않는다. 외부 알림 수신 채널은 별도 연동이다. |

## 설치·복구 순서

1. GCS에 DB 백업 전용 비공개 버킷을 만들고 VM 서비스 계정의 **버킷 범위** 접근을 확인한다. 버킷 식별자는 VM의 `/etc/docgrid/barman-offsite.json`에만 적는다.
2. `scripts/opensql/barman_offsite_sync.py`와 동기화·건강 점검 service·timer를 백업 VM에 설치한다. 예시 JSON을 실제 Barman 서버명과 비공개 버킷으로 채워 `root:barman`, `0640`으로 저장한다.
3. timer 활성화 전 `barman-offsite-sync.service`를 한 번 수동 실행해 아카이브 완료 표시와 WAL·history 객체를 확인한다. 각 파일의 바이트 수와 CRC32C를 대조한다.
4. 두 timer를 켜고 `check`가 정상인지 확인한다. GCS 업로드 실패를 한 번 주입한 후 마지막 성공 시각 유지와 다음 실행의 재시도를 확인한다.
5. 복원 시에는 원격 `complete.json`의 원래 tar 파일명을 보존하여 다운로드한다. 새 Barman에서 `import-backup`하고 후속 WAL을 복사한 뒤 격리 PITR로 사용자 데이터 시점을 대조한다. 이 복원은 별도의 후속 검증 범위다.
