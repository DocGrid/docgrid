# GCS 문서 원본의 지속 보존

## 경계와 목표

DocGrid는 원본 바이트를 GCS에, 버킷·객체 키·크기·SHA-256을 OpenSQL `file_objects`에 저장한다. 기존 [격리 복원 시험](../test-results/db-gcs-point-in-time-recovery-20261015/README.md)은 한 시점의 객체 92개를 별도 비공개 버킷으로 복사했다. 이후 업로드된 객체는 그 사본에 자동으로 추가되지 않았다.

이번 구성은 **운영 원본 버킷과 다른 비공개 버킷**의 원본 보존 경로에 새 객체를 계속 복사한다. 앱이 같은 키를 덮어쓰지 않고 새 버전에도 새 키를 쓰므로, 원본 객체의 생성 이벤트가 복사 단위다. 운영 DB와 앱 저장 코드는 바꾸지 않는다.

```text
사용자 업로드 → GcsStorageService.store() → 원본 GCS 객체 생성
                                            │ OBJECT_FINALIZE
                                            ▼
                                       Pub/Sub topic
                                            │ subscription
                                            ▼
                             Storage Transfer Service 이벤트 작업
                                            │ overwrite=never, 삭제 전파 없음
                                            ▼
                          별도 비공개 GCS 버킷의 원본 보존 경로
                                            │
                                            ▼
                    주기 감사: 키·크기·SHA-256·양쪽 generation 기록
```

[Google의 이벤트 전송 설명](https://docs.cloud.google.com/storage-transfer/docs/event-driven-google)은 생성·수정 이벤트를 통해 대상 버킷에 복사하며 **원본 삭제 이벤트를 복사본 삭제로 전파하지 않는다**고 명시한다. 전송은 비동기이며 즉시 완료나 특정 RPO를 보장하지 않는다. 업로드 직후 원본이 사라져 이벤트 전송이 원본을 읽지 못하는 경우 등은 별도 복구 절차가 필요하다. 원본 버킷의 soft delete도 독립 사본을 대신하지 않는다.

## 저장소·권한·보존

- 기존 일회성 사본이 있는 **별도 비공개 버킷과 같은 원본 보존 경로**를 재사용한다. 이전 객체 92개와 새 객체는 원본 키를 유지한다. 버킷명·프로젝트 ID·서비스 계정·원본 키는 저장소에 기록하지 않는다.
- Storage Transfer Service의 서비스 계정은 원본 버킷 조회, 대상 버킷 조회·생성, 전용 Pub/Sub 구독 권한만 얻는다. 앱의 운영 버킷 쓰기 권한은 늘리지 않는다.
- 전송 작업은 `overwrite=never`이고 삭제 옵션을 사용하지 않는다. 대상 버킷의 최소 보존 기간을 설정해 복사 직후 실수로 지우는 경로도 제한한다. **버킷 전체 객체에 적용**되므로 기존 백업 manifest도 기간 중 삭제할 수 없다. 최소 보존 기간은 자동 만료 날짜가 아니다.
- Pub/Sub notification·subscription과 전송 작업의 상태를 운영자가 조회한다. 이벤트 전달 지연·실패가 있으면 대상 버킷의 실제 객체와 감사 결과로 판단한다.

## 감사와 복원 관문

[`verify_gcs_original_preservation.py`](../../scripts/opensql/verify_gcs_original_preservation.py)는 양쪽 객체 목록의 키·크기·generation을 읽는다. **처음 발견한 generation의 원본과 사본**은 정확히 그 generation을 임시 디렉터리로 내려받아 바이트 SHA-256을 비교한다. 다음 검사부터는 이전에 해시로 검증한 원장의 양쪽 generation·크기가 그대로인 객체만 다운로드를 생략한다. GCS generation은 불변 객체 버전을 식별한다([Google Cloud 문서](https://docs.cloud.google.com/storage/docs/objects#object_immutability)). 원본·사본 generation은 복사로 달라질 수 있으므로 같은 숫자를 요구하지 않고 **각각 기록**한다. 이전 원장과 generation이 달라졌거나, 전에 검증한 사본이 사라졌거나, 새 객체의 키·크기·SHA-256이 불일치하면 실패한다. 원본이 삭제된 뒤 남은 사본은 이전 감사 원장과 generation·크기가 같을 때 보존된 사본으로 판정한다. 처음 발견한 대상 전용 객체는 다운로드·해시를 수행하고 `unattributed_retained`로 별도 집계한다. 객체별 원장은 백업 VM의 비공개 파일에만 기록하고, 공개 로그에는 건수와 안전한 단계명만 남긴다.

대상 접두사 안에 비정상적인 빈 경로 구성 요소가 있다면 해당 객체는 다운로드하지 않고 `ignored_invalid_destination`으로 계수한다. 이 건수가 0이 아니면 원인을 조사해야 한다. 이번 구성 검증에서 잘못 붙인 슬래시 때문에 생긴 시험용 사본 2건은 30일 최소 보존 정책이 끝나기 전까지 이 건수에 남는다. [최종 감사](../test-results/gcs-original-preservation-20261017/backup-vm-audit-r3.md)의 **현재 원본 94건·유효 사본 97건**과 격리된 시험 객체 2건은 서로 다른 집계다.

감사는 일별 timer로 수행한다. 전송이 아직 끝나지 않은 신생 객체는 감사 시 누락으로 보고될 수 있다. 실패 서비스 상태를 그대로 남기고 전송 작업을 확인한 뒤 다시 실행한다. 따라서 이 감사는 **현재 시점의 복사본 완전성 검사**이지 실시간 복제 SLA가 아니다.

최종 복원 검증은 다른 작업이다. OpenSQL을 시점 T로 복원한 뒤 DB가 참조하는 GCS 원본을 이 보존 경로에서 찾고, 바이트 해시와 사용자 기능을 확인해야 복원 가능 범위를 말할 수 있다.

## 설치 순서

1. 운영 원본·별도 비공개 대상 버킷을 확인하고 기존 사본의 키 접두사가 원본 키를 그대로 보존하는지 검사한다.
2. Storage Transfer Service API, 전용 Pub/Sub topic·subscription, GCS `OBJECT_FINALIZE` notification과 버킷 범위 IAM을 구성한다. `gcloud transfer authorize --add-missing`처럼 프로젝트 전체에 넓은 역할을 일괄 부여하지 않는다.
3. 대상 접두사의 끝 슬래시를 한 번만 붙인 뒤 전송 작업을 이벤트 기반·덮어쓰기 금지·삭제 옵션 없음으로 만들고, 기존 일회성 사본 이후의 누락 객체를 별도로 채운다. 생성 후 실제 `gcsDataSink.path`를 기존 사본 경로와 대조한다.
4. 시험용 서로 다른 두 키를 생성해 복사본의 바이트·generation을 확인한다. 원본 한 건을 삭제한 뒤에도 사본이 남는지 확인하고, 시험 객체의 대상 버킷 최소 보존 기간을 고려해 정리 범위를 기록한다.
5. 감사 도구·service·timer를 백업 VM에 설치한다. 원본 버킷·대상 버킷 읽기 권한만 백업 VM 서비스 계정에 추가하고, 객체별 비공개 원장과 안전한 요약 로그를 분리한다.
