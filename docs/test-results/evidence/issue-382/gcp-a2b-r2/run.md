# GCP A→B 실제 WebSocket 전달 — `ab382-a2b-r2`

| 항목 | 기록 |
| --- | --- |
| 목적 | A만 합성 대시보드 메시지를 발행하고 B의 독립 SimpleBroker 구독자가 수신하는지 확인 |
| 실행 위치 | GCP 백엔드 A/B, 공용 Redis VM, OpenSQL 3노드·OpenProxy 2대, 별도 GCP 부하 VM |
| 코드 기준 | `fix/382` 커밋 `9cd1c10b8d1d14fdc70184ee08e974905e5c8880`; 양쪽 `compileTestJava` 종료 코드 0 |
| 절차·명령 | A/B `launch_ab_dashboard_fixture.sh`(A `publish=true`, B `publish=false`); 부하 VM `websocket_dashboard_revocation.py --observe-only --count-only --clients 50 --clients-b 25 --before-seconds 5 --after-seconds 15` |
| 성공 조건 | A/B 각 25개 구독 준비, B 실제 `MESSAGE` 1건 이상, B 합성 발행 0건, 오류 없이 정상 정리 |
| 관측 시간 | 2026-10-02 14:11:05–14:11:25 KST, 클라이언트 수신 시각 기준 |
| 수신 결과 | 구독 50/50(A 25, B 25); A **1,700건**, B **1,275건**; STOMP 오류·연결 종료 이벤트 0건 |
| 서버 이벤트 | 전체 fixture 실행 중 A 발행 성공 566회, B `미발행` 652회; 각 서버 최대 인증 세션 25개. 서버 이벤트 집계 범위는 위 20초 수신 창보다 길다 |
| 종료·해석 | A/B fixture 종료 코드 0, 양쪽 시험 계정 각 2개 정리. B가 자체 합성 발행 없이 프레임을 받았으므로 교차 백엔드 전달이 실제 환경에서 관측됐다. B 페이로드의 모든 필드·DB 쿼리 출처를 이 수신 계측만으로 개별 증명한 것은 아니다 |

원본: [클라이언트 이벤트](client-events.jsonl), [A 발행 이벤트](A-publisher.tsv), [B 미발행 이벤트](B-publisher.tsv). JSONL은 수신 시각·서버 역할·구독 번호·이벤트 종류만 기록했고, 토큰·IP·계정·프로젝트 ID를 기록 전에 제외했다.
