# #401 GCP Rocky init 후보 `/proc` 시험

- 실행 ID: `issue-401-gcp-init-candidate-03`
- 목적: 앞선 두 실행의 진단 명령 부재 가능성을 제거하고 기본 이미지에서 Docker init의 실제 PID 1과 고아 자식 회수를 확인한다.
- 실행 위치: GCP DB replica VM의 독립 임시 컨테이너.
- 절차: 후보 0개 확인 → `--init` 기동 → Docker inspect `Init` 및 `/proc/1/comm` → 고아 자식 종료 → 호스트 `docker top`의 좀비 수 → 후보 삭제.
- 성공 기준: init true, PID 1 `docker-init`/`tini`, 좀비 0, 정리 1/1, DB 컨테이너 변경 0건.
- 결과: 이전 후보 **0개**. Docker `Init=true`, 컨테이너 `/proc/1/comm=docker-init` 확인. 호스트 `docker top -eo stat,comm`은 Docker가 PID 필드를 요구해 종료 코드 **1**이므로 좀비 수 미측정. 오류 트랩으로 후보 삭제를 시도했고, 다음 실행의 시작에서 잔존 0개를 확인한다.
- 해석: 실제 GCP Rocky 기본 이미지에서 `--init` PID 1 전환은 확인했다. 자식 회수와 cleanup은 별도 확인 전이다. DB 컨테이너 변경 0건.
