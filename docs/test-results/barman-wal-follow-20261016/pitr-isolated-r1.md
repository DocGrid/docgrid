# DR-BARMAN-PITR-ISOLATED-20261016-R1 — 첫 격리 시작 실패

- 시각: 2026-10-16 22:50:57~22:51:05 KST.
- 목적·위치: GCP 시험 Standby 1대, 별도 stage 및 localhost 포트 15432에서 목표 시각 복원.
- 방법: 15분 원복 타이머를 먼저 설정하고 기존 Standby 컨테이너를 중단한 뒤 호스트 PostgreSQL을 격리 stage로 기동.
- 통과 기준: A 1건·B 0건, 복구 모드 종료, 원래 Standby 자동 재합류.
- 관측: 격리 PostgreSQL은 라이선스 오류로 약 0.156초 만에 실패. 타이머는 `docker start`만 수행했고, 원래 컨테이너의 기본 명령이 `sleep infinity`여서 Patroni·PostgreSQL이 자동으로 시작되지 않았다. 22:57:27~22:57:42 KST 직접 bootstrap 명령으로 재합류를 복구했다. Primary와 다른 Standby는 유지됐다.
- 판정: **실패**. 약 6분간의 구간에서 Standby 1대가 정상 복제에 참여하지 못한 사실을 숨기지 않는다. 첫 타이머는 제거했고 뒤의 Run에서 bootstrap을 포함하는 원복으로 수정했다. 정확한 중단 지속 시간은 계측하지 않았다. 원격 전체 stdout 미보존; 도구 출력의 사후 비식별 요약.
