# Primary postmaster 종료 뒤 업로드 — `ha488upload02`

- 목적: PostgreSQL Primary 프로세스 장애를 실제로 주입한 회차에서 PDF 업로드·동일 키 재전송·영속 상태를 확인.
- 실행 위치·방법: Patroni 리더 1·streaming 복제본 2(지연 0), etcd 3/3 healthy 확인 후 현재 Primary VM에서 기존 `ha_primary_process_fault.sh`로 postmaster만 SIGKILL. 운영자 컴퓨터의 IAP 터널에서 `verify_initial_upload_retry.py --mode primary-process-fault --run-id ha488upload02` 실행.
- 성공 기준: 장애 뒤 클러스터가 리더 1·복제본 2로 복구, 업로드·재전송 결과 수렴, DB 완료 요청·파일·Job 각 1, GCS 해시 일치.
- 기간: 2026-10-14 00:33:15.958~00:35:17.662 KST. 합성 PDF 624바이트, SHA-256 접두 `f6908eb6bd30`.

| 단계·시각(KST) | 실제 관측 |
| --- | --- |
| 00:33:16.553 / 17.156 | 회원가입 201·로그인 200 후 장애 확인 대기 |
| 장애 주입 | 현재 Primary postmaster SIGKILL 성공. VM·Patroni·etcd는 중단하지 않음 |
| 00:33:46.510 / 54.529 | 장애 신호 확인 뒤 A 최초 업로드 201; HTTP 500·503은 관측되지 않음 |
| 재전송 전 | Patroni 리더 1·streaming 복제본 2, 최대 lag 0, timeline 17로 수렴 |
| 00:34:52.254 | B 동일 키 재전송 201, 500.4ms; ID 4/4 동일 |
| 00:34:53.022 / 54.510 | 다른 입력 409, A/B 다운로드 200×2·해시 2/2 일치 |
| 00:35:17.662 | Primary DB 완료 요청·파일·Job 각 1, 참조 GCS 객체 1건 624바이트 SHA 일치, 문서·버전 `UPLOADED` |

판정: 프로세스 장애 후 클러스터와 이 요청의 영속 상태가 정상으로 수렴했다. 후속 DB 집계에서 문서·버전·Outbox도 각각 1건이었다. 업로드가 DB 불가 구간과 정확히 겹쳤다는 증거는 없고 첫 응답도 201이므로, **결과 불명·500/503 복구 실측으로 주장하지 않는다.** VM 상실·새 리더 승격 시험도 아니다.
