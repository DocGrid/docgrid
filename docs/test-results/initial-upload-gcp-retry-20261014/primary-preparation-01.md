# Primary 프로세스 장애 첫 시도 실패 — `ha488upload01`

- 목적: GCP Primary postmaster 장애 중 최초 PDF 업로드와 같은 키 재시도를 실행하려 했다.
- 실행 위치·방법: 운영자 컴퓨터의 IAP 터널 하네스 `verify_initial_upload_retry.py --mode primary-process-fault --run-id ha488upload01`; 현재 Primary VM에 기존 `ha_primary_process_fault.sh`를 일시 배치하고, PostgreSQL postmaster만 SIGKILL. Patroni·etcd는 유지.
- 성공 기준: 장애 확인 뒤 실제 HTTP 업로드와 복구 후 재전송 및 DB·GCS 대조. 이 회차는 기준 미달.
- 기간: 2026-10-14 00:29:35.526~00:31:06.685 KST(하네스); 복구 상태는 그 직후 확인.

| 단계 | 실제 관측 |
| --- | --- |
| 하네스 준비 | 합성 PDF 624바이트, SHA-256 접두 `611b8b14d137`; 회원가입 201·로그인 200 |
| 장애 주입 | 현재 Primary 확인 뒤 postmaster SIGKILL 명령 성공 |
| 하네스 실패 | 일시 배치 시간이 길어 `장애확인:TIMEOUT`(90초). 업로드 HTTP 요청 **0건**, 재전송 **0건** |
| 복구 확인 | 원래 노드 Primary running, 나머지 2노드 Replica running, timeline 17 유지 |

판정: **실패한 준비 회차**. DB 프로세스 장애는 발생했으나 업로드 결과를 측정하지 못했다. 성공 통계에 합치지 않고 새 ID `ha488upload02`로 다시 실행했다.
