# DB 백업 전용 GCS 버킷 설정 확인 · BUCKET-R1

| 항목 | 값 |
| --- | --- |
| 목적 | 백업 VM 밖의 독립 저장소를 만들고 공개 접근·보존·권한을 확인 |
| 실행 위치 | GCP 시험 프로젝트의 DB 백업 전용 버킷 |
| 절차 | `gsutil mb` → `gsutil pap set` → `gsutil retention set` → 버킷 한정 IAM 부여 → `pap get`·`retention get`·`ubla get`·`iam get` 재조회 |
| 실행 ID | `BARMAN-OFFSITE-BUCKET-20261017-R1` |
| 성공 기준 | 전용 버킷 존재, public access prevention 적용, uniform bucket-level access 적용, 30일 보존, 공개 IAM 구성원 0명 |

2026-10-17 00:40 KST 버킷 생성과 다섯 설정 단계가 모두 성공했다. 재조회에서 공개 접근 차단·균일 접근·30일 보존 정책(잠금 없음)과 공개 IAM 구성원 0명을 확인했다. 이번 기능을 위해 백업 VM 서비스 계정에 **해당 버킷 범위의** 객체 생성·조회 권한을 추가했다. 계정의 다른 기존 권한은 이 시험에서 평가하지 않았다. 버킷 이름·프로젝트·서비스 계정 식별자는 이 기록에 남기지 않는다.
