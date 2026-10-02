# GCP B→A 실제 WebSocket 전달 — `ab382-b2a`

| 항목 | 기록 |
| --- | --- |
| 목적 | 반대 방향에서도 B 발행이 A의 독립 SimpleBroker 구독자에게 전달되는지 확인 |
| 실행 위치 | GCP 백엔드 A/B, 공용 Redis VM, OpenSQL 3노드·OpenProxy 2대, 별도 GCP 부하 VM |
| 코드 기준 | `fix/382` 커밋 `9cd1c10b8d1d14fdc70184ee08e974905e5c8880` |
| 절차·명령 | A/B `launch_ab_dashboard_fixture.sh`(A `publish=false`, B `publish=true`); 부하 VM `websocket_dashboard_revocation.py --observe-only --count-only --clients 50 --clients-b 25 --before-seconds 5 --after-seconds 15` |
| 성공 조건 | A/B 각 25개 구독 준비, A 실제 `MESSAGE` 1건 이상, A 합성 발행 0건, 정상 정리 |
| 관측 시간 | 2026-10-02 14:15:19–14:15:38 KST, 클라이언트 수신 시각 기준 |
| 수신 결과 | 구독 50/50(A 25, B 25); A **1,250건**, B **1,700건**; STOMP 오류·연결 종료 이벤트 0건 |
| 서버 이벤트 | 전체 fixture 실행 중 A `미발행` 391회, B 발행 성공 401회; 각 서버 최대 인증 세션 25개. 서버 이벤트 집계 범위는 위 20초 수신 창보다 길다 |
| 종료·해석 | A/B fixture 종료 코드 0, 양쪽 시험 계정 각 2개 정리. A→B뿐 아니라 B→A도 실제 환경에서 수신 확인 |

원본: [클라이언트 이벤트](client-events.jsonl), [A 미발행 이벤트](A-publisher.tsv), [B 발행 이벤트](B-publisher.tsv). 비식별 이벤트에는 내부 주소·토큰·사용자 식별자를 넣지 않았다.
