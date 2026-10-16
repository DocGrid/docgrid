# GCS 문서 원본의 지속 보존 검증

## 판정 범위

기존의 별도 비공개 버킷에 있던 일회성 원본 사본 92건을 기반으로, 새 원본 생성 이벤트를 Storage Transfer Service로 같은 접두사에 복사하도록 구성했다. 전송 작업은 **덮어쓰기 금지·삭제 전파 없음**, 대상 버킷은 **30일 최소 보존**이다. 마지막 조회에서도 이벤트 구독이 연결된 작업은 `ENABLED`, 덮어쓰기 `NEVER`, 삭제 옵션 미설정이었다. 전송은 비동기이므로 업로드 직후 원본이 사라지는 경우까지 RPO 0으로 보장하지 않는다.

경로를 바로잡은 뒤 새 시험 객체 **2/2건**이 복사됐고 원본·사본의 크기와 SHA-256이 일치했다. 시험용 원본 한 건을 삭제해도 올바른 접두사의 사본은 즉시·30초 후에 남았다. 전체 감사에서는 한 시점의 현재 원본 **95/95건**, 삭제 시험 후 **96건의 현재 원본과 97건의 유효 사본**을 바이트까지 대조했다. 후자에는 삭제된 원본의 사본 1건이 포함된다.

백업 VM의 첫 전체 감사에서는 시작 시점 원본·사본 **95/95건**을 다운로드해 SHA-256을 계산했다. 이후 일별 감사기를 증분 방식으로 바꿔 새 원본 **1건**·새 보존본 **2건**만 내려받아 검사했다. 시험용 원본 2건을 정리한 최종 회차에서는 현재 문서 원본 **94건**, 유효 보존본 **97건**(삭제된 시험 원본 사본 3건 포함), 누락·generation 변경 **0건**이었다. 첫 전체 검사는 약 19분, 증분 검사는 새 generation만 다운로드했다. 감사 timer와 객체별 비공개 원장(`0600`)도 백업 VM에서 확인했다.

초기 경로 끝에 `/`를 두 번 붙여 시험 사본 2건을 잘못된 상대 경로에 생성한 실패를 기록했다. 올바른 경로의 92건은 변경되지 않았다. 대상 버킷의 보존 기간 때문에 잘못된 시험 사본은 즉시 정리하지 않고 감사에서 격리 건수 2건으로 표시한다. 원본·사본의 객체별 키·SHA-256·generation 원장은 비공개 파일에만 보관하며, 공개 기록은 건수와 안전한 상태만 포함한다.

## 실행별 기록

| 구분 | 실행 ID | 결과 | 기록 |
| --- | --- | --- | --- |
| 로컬 단위 시험 | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R1` | 5/5 통과 | [R1](local-unit-r1.md) |
| 로컬 단위 시험 | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R2` | 7/7 통과 | [R2](local-unit-r2.md) |
| 로컬 단위 시험 | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R3` | 8/8 통과 | [R3](local-unit-r3.md) |
| 로컬 단위 시험 | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R4` | 10/10 통과 | [R4](local-unit-r4.md) |
| 증분 감사 단위 시험 | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R5` | 실제 GCS에서 불가능한 입력으로 13/14 통과 | [R5](local-unit-r5.md) |
| 증분 감사 단위 시험 | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R6` | 14/14 통과 | [R6](local-unit-r6.md) |
| generation 지정 회귀 시험 | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R7` | 15/15 통과 | [R7](local-unit-r7.md) |
| Python 문법 검사 | `GCS-ORIGINAL-LOCAL-SYNTAX-20261017-R1` | 캐시 경로 권한으로 미완료 | [R1](local-syntax-r1.md) |
| Python 문법 재검사 | `GCS-ORIGINAL-LOCAL-SYNTAX-20261017-R2` | 2/2개 통과 | [R2](local-syntax-r2.md) |
| 클라우드 구성 | `GCS-ORIGINAL-CLOUD-SETUP-20261017-R1` | 초기 경로 오류 발견·수정, 최종 작업 활성 | [구성](cloud-setup-r1.md) |
| 기준선 감사 | `GCS-ORIGINAL-AUDIT-BASELINE-20261017-R1` | 로컬 병렬 CLI 제약으로 실패 | [R1](audit-baseline-r1.md) |
| 기준선 감사 | `GCS-ORIGINAL-AUDIT-BASELINE-20261017-R2` | 대상 디렉터리 선행 조건 누락으로 실패 | [R2](audit-baseline-r2.md) |
| 기준선 감사 | `GCS-ORIGINAL-AUDIT-BASELINE-20261017-R3` | 느린 CLI 다운로드를 중단, 결과 없음 | [R3](audit-baseline-r3.md) |
| 보충 복사 | `GCS-ORIGINAL-BACKFILL-20261017-R1` | 누락 3건만 덮어쓰기 없이 추가 | [보충](backfill-r1.md) |
| 바이트 감사 | `GCS-ORIGINAL-AUDIT-AFTER-BACKFILL-20261017-R4` | 현재 원본 95/95건 일치 | [R4](audit-after-backfill-r4.md) |
| 잘못된 경로 시험 | `GCS-ORIGINAL-EVENT-COPY-20261017-R1` | 복사는 됐으나 대상 경로 불일치 | [R1](event-copy-r1.md) |
| 잘못된 경로의 삭제 시험 | `GCS-ORIGINAL-DELETE-RETENTION-20261017-R1` | 사본 유지 관측, 정확한 경로 검증에는 사용하지 않음 | [R1](delete-retention-r1.md) |
| 정확한 경로 시험 | `GCS-ORIGINAL-EVENT-COPY-20261017-R2` | 새 객체 2/2건 바이트 일치 | [R2](event-copy-r2.md) |
| 원본 삭제 시험 | `GCS-ORIGINAL-DELETE-RETENTION-20261017-R2` | 원본 삭제 후 사본 즉시·30초 유지 | [R2](delete-retention-r2.md) |
| 삭제 후 바이트 감사 | `GCS-ORIGINAL-AUDIT-AFTER-DELETE-20261017-R5` | 현재 원본 96건·사본 97건 일치, retained 1건 | [R5](audit-after-delete-r5.md) |
| generation 지정 읽기 | `GCS-ORIGINAL-PINNED-READ-20261017-R1` | 1/1건 크기·SHA-256 일치 | [읽기 시험](pinned-generation-read-r1.md) |
| 백업 VM 첫 전체 감사 | `GCS-ORIGINAL-BACKUP-VM-AUDIT-20261017-R1` | 시작 시점 원본·사본 95/95건 일치, timer 활성 | [R1](backup-vm-audit-r1.md) |
| 백업 VM 증분 감사 | `GCS-ORIGINAL-BACKUP-VM-AUDIT-20261017-R2` | 원본 96·사본 97건, 새 원본 1·사본 2건만 다운로드 | [R2](backup-vm-audit-r2.md) |
| 시험용 원본 정리 | `GCS-ORIGINAL-PROBE-CLEANUP-20261017-R1` | 해시로 식별한 원본 2/2건 삭제 | [정리](probe-cleanup-r1.md) |
| 정리 후 로컬 감사 | `GCS-ORIGINAL-AUDIT-AFTER-CLEANUP-20261017-R6` | 현재 원본 94·사본 97건, retained 3건 | [R6](audit-after-cleanup-r6.md) |
| 정리 후 백업 VM 감사 | `GCS-ORIGINAL-BACKUP-VM-AUDIT-20261017-R3` | 현재 원본 94·사본 97건, retained 3건, timer 활성 | [R3](backup-vm-audit-r3.md) |

## 남은 경계

- 보존본이 생성되기 전 원본이 없어지면 이벤트 전송만으로는 되살릴 수 없다. 별도 복구 절차와 전송 지연 관측이 필요하다.
- DB를 특정 시점으로 복원했을 때 그 시점의 모든 `file_objects` 참조를 이 **지속 보존 경로**에서 다시 대조하는 통합 복원 시험은 별도 작업이다. 기존 격리 시점 복원 시험은 일회성 사본을 사용했다.
- 잘못된 시험 객체 2건은 30일 보존 정책 때문에 현재 정리하지 않았다. 만료 후 비공개 원장과 대조해 시험 키만 삭제해야 한다.
