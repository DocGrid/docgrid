# DR-BARMAN-PATRONI-MAPPING-20261016-R1 — Patroni 멤버와 VM 순번 대응

- 시각: 2026-10-16 22:13:46~22:13:57 KST.
- 목적·위치: GCP 현재 리더의 Patroni `/cluster`를 읽어 전환 대상 매핑을 확인.
- 방법: 각 멤버의 REST `host`·`api_url`이 세 VM 내부 주소 중 어느 것과 일치하는지 순번만 기록.
- 통과 기준: 멤버 3개가 VM 1·2·3과 각각 유일하게 대응하고 역할이 Primary 1·Standby 2.
- 관측: 세 멤버가 각각 순번 1·2·3에 일치, 역할은 replica·leader·replica. 내부 이름·주소 미기록. 임시 OS Login 키 제거 명령 성공.
- 판정: **통과**.
