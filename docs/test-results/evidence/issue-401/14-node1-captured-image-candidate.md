# #401 캡처 이미지 격리 후보 시험

- 실행 ID: `issue-401-captured-candidate-01`
- 목적: node1 기존 실행 계층을 보존한 로컬 이미지가 `--init`과 함께 실행되며 필수 진단 패키지도 유지하는지 확인한다.
- 실행 위치: GCP node1 DB VM의 `--network none`·데이터 마운트 없는 임시 컨테이너.
- 절차: 캡처 이미지로 별도 이름의 임시 컨테이너 실행 → Docker init/PID 1/명령 존재/RPM 개수/좀비 수 확인 → 후보 삭제.
- 성공 기준: init=true, PID 1 docker-init, RPM 개수 기존 컨테이너와 같음, `ps`·`jq` 사용 가능, 종료 자식 좀비 0, 임시 후보 정리 1/1, 운영 DB 컨테이너 변경 0.
- 결과: `Init=true`, PID 1 `docker-init`; 기존/캡처 이미지의 RPM **168/168개**, `ps`·`jq` **2/2개** 사용 가능. 종료된 고아 자식의 좀비 **0개**, 임시 후보 삭제 **1/1**, 운영 DB 컨테이너 변경 **0건**.
- 판정: 캡처 이미지가 확인 가능한 OS 패키지를 보존하면서 Docker init을 실행한다. 실제 Patroni·PostgreSQL 서비스 부팅과 계획된 강등은 아직 미검증이다.
