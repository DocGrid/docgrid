# #401 GCP Rocky init 후보 최종 재시험

- 실행 ID: `issue-401-gcp-init-candidate-04`
- 목적: PID가 포함된 호스트 `docker top` 명령으로 앞선 미측정 항목(고아 자식 회수·후보 정리)을 완료한다.
- 실행 위치: GCP DB replica VM의 독립 임시 컨테이너.
- 절차: 이전 후보 잔존 확인 → 동일 이미지 `--init` 기동 → PID 1 확인 → 고아 자식 종료 → `docker top -eo pid,ppid,stat,comm` → 후보 삭제 확인.
- 성공 기준: 이전 후보 0, init=true, PID 1 docker-init, 종료 자식 좀비 0, 후보 삭제 1/1, DB 컨테이너 변경 0.
- 결과: 이전 후보 **0개**, `Init=true`, PID 1 `docker-init`, 0.1초 뒤 종료하는 고아 자식의 2초 후 좀비 **0개**, 새 후보 정리 **1/1**. 운영 DB 컨테이너 변경 **0건**.
- 판정: 실제 GCP Rocky 기본 이미지에서 `--init` 자체는 기대대로 동작한다. Patroni 강등·재합류는 아직 미검증이다.
