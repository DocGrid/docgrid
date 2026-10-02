# GCP Redis 재시작 전후 전달 — `ab382-redis-restart`

| 항목 | 기록 |
| --- | --- |
| 목적 | 공용 Redis 서비스가 짧게 재시작된 뒤 A/B 구독자가 계속 갱신 메시지를 받는지 확인 |
| 실행 위치 | GCP 백엔드 A/B, 공용 Redis VM, OpenSQL 3노드·OpenProxy, 별도 GCP 부하 VM |
| 코드 기준 | 앱 `9cd1c10b8d1d14fdc70184ee08e974905e5c8880` |
| 절차·명령 | A 발행/B 미발행 fixture; 부하 VM `websocket_dashboard_revocation.py --observe-only --count-only --clients 50 --clients-b 25 --before-seconds 10 --after-seconds 25`; Redis VM `systemctl restart redis` |
| 성공 조건 | 재시작 직후 Redis unit `active`, A/B 양쪽에 재시작 이후 새 `MESSAGE` 1건 이상, 시험 계정 정리 |
| 장애 시각 | 2026-10-02 **14:24:34 KST** 시작·종료(초 단위 기록). `systemctl restart` 종료 코드 0, Redis unit `active` |
| 수신 결과 | 구독 50/50. 전체 35초 관측에서 A **2,967건**, B **2,100건**. 14:24:35 KST 이후 A **550건**, B **400건**. 이후 첫 관측 프레임 A 14:24:35.159, B 14:24:35.284 KST |
| 서버 이벤트 | 전체 fixture 실행 중 A 발행 성공 800회, B `미발행` 886회; 각 서버 최대 인증 세션 25개 |
| 종료·해석 | A/B fixture 종료 코드 0, 양쪽 시험 계정 각 2개 정리, Redis `active`. 짧은 재시작 뒤 수신 회복은 관측됐지만, 5초 이상 지속 단절에서 놓친 신호의 재동기화나 엄밀한 재구독 완료 시각은 측정하지 않았다 |

원본: [클라이언트 이벤트](client-events.jsonl), [요약](summary.json), [A 발행 이벤트](A-publisher.tsv), [B 미발행 이벤트](B-publisher.tsv). 전후 건수는 이벤트의 KST 수신 시각과 14:24:35 경계로 계산했다. 짧은 재시작의 정상 300ms 발행 간격을 Redis 다운타임으로 오인하지 않는다.
