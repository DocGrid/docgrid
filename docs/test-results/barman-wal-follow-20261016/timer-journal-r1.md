# DR-BARMAN-TIMER-JOURNAL-20261016-R1 — 감시기 로그 첫 조회

- 시각: 2026-10-16 22:20:45~22:20:54 KST.
- 목적·위치: GCP 백업 VM의 systemd journal에서 리더 전환 직후 감시기 실행 여부 확인.
- 방법: `journalctl -u barman-wal-follow-primary.service`의 실행 시각 주변 구간을 JSON으로 읽고 안전 필드만 추출.
- 통과 기준: 해당 구간의 감시기 실행 시각과 모델 전환 여부 확인.
- 관측: 구조화 감시기 이벤트 0건. 임시 OS Login 키 제거 명령 성공.
- 판정: **미확인**. VM이 UTC인데 KST 시각을 현지 시각으로 조회했다. [UTC 재조회](timer-journal-r2.md)에서 확인했다.
