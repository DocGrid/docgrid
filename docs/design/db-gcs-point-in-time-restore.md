# OpenSQL·GCS 시점 복원 설계

## 목표와 현재 관문

DB가 문서·버전·Job·청크를, GCS가 PDF·DOCX 원본을 보관하는 DocGrid에서 **사용자가 다시 다운로드·검색·업로드할 수 있는 상태**를 복원 완료로 정의한다. 이 문서는 실행 계획이다. [2026-10-15 준비 점검](../test-results/db-gcs-restore-preflight-20261015/README.md)에서 Primary의 연속 WAL 보관과 독립 GCS 원본 백업이 확인되지 않아 현재 PITR 실험은 `NO-GO`다.

```text
사용자 업로드 → GCS 객체 저장 → OpenSQL FileObject·Version·Job 기록
                     │                         │
                     └─ 독립 원본 보존본          └─ 기준 백업 + 연속 WAL
                               │                         │
                               └──── 격리 복원 시점 T ─────┘
                                             ↓
                              DB 참조 ↔ GCS 바이트 대조
                                             ↓
                         백엔드·Worker 기동 및 사용자 경로 시험
```

## 복원 대상과 원장

1. DB 기준 백업과 WAL은 운영 OpenSQL의 실패 범위와 분리된 위치에 보관한다. **디스크 스냅샷 목록만으로 PITR 가능이라고 판정하지 않는다.** PostgreSQL 호환 백업 도구와 OpenSQL 바이너리·Patroni 구성의 적합성을 격리 노드에서 먼저 검증한다.
2. GCS 원본은 운영 버킷과 별도 보존 위치에 복사하고, 객체별 원본 버킷·키·크기·SHA-256·generation·복사 완료 시각을 기록한다. 현재 7일 soft delete는 독립 백업을 대신하지 않는다. 백업 시점 이후 생성된 객체도 복원 대상 시점 T의 DB가 참조한다면 필요한 원본이므로, 복사 지연과 누락을 명시적으로 측정한다.
3. 시점 T로 복원한 DB의 `file_objects`를 기준 원장으로 삼는다. `document_versions.file_object_id`로 도달 가능한 원본을 우선 검증하고, 미완료 최초·새 버전 업로드 요청의 후보 위치는 **완료 원본과 분리**해 판정한다. DB에 원본의 영구 generation 컬럼은 없으므로, 백업 원장의 generation을 사용하되 최종 판정에는 실제 바이트 SHA-256을 다시 계산한다.
4. 격리 복원 버킷으로 옮기는 경우 DB에 저장된 `file_objects.bucket_name`과 두 업로드 요청 원장의 후보 버킷이 원래 버킷을 가리킨다. `GcsStorageService`가 설정 버킷과 DB 버킷의 일치를 요구하므로, **격리 DB에서만** 복원 버킷으로 참조를 바꾸는 명시적 단계가 필요하다. 변경 전후 행 수·해시를 남기고 운영 DB는 수정하지 않는다.

## 백업 수집과 격리 위치의 실행 관문

OpenSQL 공급사의 [Barman 구성 안내](https://docs.tibero.com/tmaxopensql/installation/configuration/barman)는 별도 Barman 서버의 `backup_method = postgres`, `streaming_archiver = on`, 물리 복제 슬롯, `barman check`를 안내한다. 이 방식을 첫 후보로 삼되, 다음을 실제 적용 전에 확인한다.

1. 백업 수신 VM은 **외부 IP 없이** 운영 DB와 같은 VPC의 전용 서브넷에 둔다. SSH는 IAP 경로만 열고, 패키지 설치가 필요한 기간에만 그 서브넷 전용 Cloud NAT를 사용한다. 백업 원본용 GCS 버킷은 공개 접근을 차단하고, VM 서비스 계정의 쓰기 권한을 그 버킷으로 제한한다. 이는 실행안이며 아직 자원을 생성한 결과가 아니다.
2. [용량 점검](../test-results/db-gcs-restore-preflight-20261015/runs/capacity-r3.md)에서 복제 슬롯 한도 10개 중 현재 2개가 사용 중임을 확인했다. **한도 여유가 백업 성공을 뜻하지는 않는다.** 슬롯 생성 전 `pg_hba.conf`의 백업 수신 주소, 실제 WAL 발생량, 디스크 여유, 수신 중단 시 WAL 보존량의 상한·경보·슬롯 정리 절차를 확정한다. 수신이 끊긴 채 슬롯만 남으면 운영 디스크가 가득 찰 수 있다.
3. 기준 백업과 연속 WAL은 서로 맞는 동일 클러스터·타임라인에서 수집하고 `barman check`와 **격리 복원**으로 검증한다. WAL 수신은 현재 Primary에 연결하고, Patroni 역할 변경 뒤 새 Primary로 옮기는 절차는 별도 시험 결과가 나오기 전까지 자동 복구라고 표현하지 않는다.
4. 기존 OpenSQL 체험판 라이선스 3개는 각각 기존 호스트에 묶여 있다. 새 복원 VM에 이를 복사해 실행하지 않는다. 별도 복원용 라이선스가 없다면 기존 라이선스 호스트의 **운영 프로세스·포트·데이터 경로와 분리된 컨테이너**가 허용되는지 먼저 확인한다. 허용되지 않으면 OpenSQL 복원 시험을 중단하고 추가 라이선스를 확보한다. PostgreSQL 표준 바이너리로 대체한 결과를 OpenSQL 복원 성공으로 보고하지 않는다.
5. GCS 보존본은 원본 버킷과 별도 버킷에 복사하고, 복사 시점·객체 generation·바이트 해시를 원장에 남긴다. 백업 시점 T의 DB가 참조하는 객체가 **모두** 보존본에 있음을 확인한 뒤에만 시점 복원 시험을 시작한다. 초기 1회 복사만 완료했다면 상시 백업이 된 것처럼 표현하지 않는다.

## 실행 순서와 실패 시 처리

```text
1. 사전 관문
   기준 백업 복원 가능 · WAL 연속성 · GCS 보존본 · 격리 DB/버킷 · 자격 분리
   하나라도 미확인 → 실행 중지, NO-GO 기록

2. 격리 복원
   OpenSQL 기준 백업 복원 → 목표 시점 T까지 WAL 적용
   → 복원 DB의 참조 원본 목록 고정 → 보존본에서 격리 GCS로 복사

3. 정합성 관문
   원본 누락·바이트 수·SHA-256 불일치·잘못된 버전 참조 집계
   불일치 > 0 → 백엔드 시작 금지, 원인과 재실행 ID 기록
   GCS에만 있는 T 이후 객체 → 즉시 삭제하지 않고 격리 목록에 분류

4. 사용자 관문
   백엔드·Worker 시작 → 이전 문서 다운로드 → 현재 버전 검색
   → 미완료 Job 재개/중복 대조 → 새 문서 업로드·다운로드
```

복원된 GCS 객체에는 새 generation이 할당될 수 있다. 복원 전 generation과 수치가 같다는 것을 합격 기준으로 삼지 않고 **현재 DB 참조와 복원본의 바이트·해시 일치**를 확인한다. 후보 정리 작업은 복원 중에는 끄고, 후보 원장과 복원된 객체를 분리 검토한 뒤 다시 켠다.

## 첫 격리 시험의 판정표

| 시점·상태 | 기대 결과 | 실패로 기록할 조건 |
|---|---|---|
| T 이전 문서 A와 버전 | DB 존재, GCS 원본 다운로드·해시 일치, 검색 가능 | DB 참조 원본 누락, 해시 불일치, 다른 버전 인용 |
| T 이후 문서 B | 복원 DB에는 없음; 격리 GCS의 여분 객체는 서비스에 노출되지 않음 | B가 문서 목록·검색에 노출되거나 정리 중 A가 삭제됨 |
| T 당시 미완료 Job | 재개·수렴 후 Job/활성 청크 중복 0 | 완료 누락 또는 중복 활성 청크 |
| 서비스 재개 | 새 문서 C 업로드·다운로드 성공 | 다운로드·검색·쓰기 중 하나라도 실패 |
| 시간 | 복원 시작부터 사용자 관문 통과까지 실제 RTO, 복원 시점과 성공 응답 원장으로 실제 RPO 기록 | DB 기동 시간만을 서비스 RTO로 표기하거나 의도한 T 이후 손실을 숨김 |

처음에는 합성 문서와 격리 DB·버킷에서 실행한다. 실제 운영 클러스터를 정지하거나 운영 버킷을 복원 대상으로 쓰지 않는다. 완료 후에는 복원 자원·임시 키·백업 사본의 보존/삭제 결과를 실행 ID별로 기록한다.

## 확인한 코드와 외부 동작

- [`FileObject`](../../backend/src/main/java/com/opensource/docgrid/domain/document/entity/FileObject.java)는 버킷·객체 키·크기·해시를 보관한다.
- [`GcsStorageService`](../../backend/src/main/java/com/opensource/docgrid/domain/document/storage/GcsStorageService.java)는 같은 키의 덮어쓰기를 막고, DB에 기록된 버킷이 현재 설정 버킷과 일치하는지 검사한다.
- [최초 업로드 후보](../../backend/src/main/resources/db/migration/V48__track_initial_upload_candidates.sql)와 [새 버전 후보](../../backend/src/main/resources/db/migration/V50__track_version_upload_candidates.sql)는 별도 요청 원장에 저장된다.
- PostgreSQL의 [연속 WAL 보관·PITR 문서](https://www.postgresql.org/docs/17/continuous-archiving.html)는 기준 백업과 이어지는 WAL이 필요함을 설명한다. GCS의 [soft delete 복원 문서](https://docs.cloud.google.com/storage/docs/soft-delete)는 복원된 객체에 새 generation이 할당됨을 명시한다.
