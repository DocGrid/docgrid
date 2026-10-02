# GCP 양쪽 구독 중 관리자 역할 회수 — `ab382-rev-a2b`

| 항목 | 기록 |
| --- | --- |
| 목적 | 양쪽 백엔드가 같은 시험 ADMIN 구독자를 처리하는 동안 실제 역할 회수 API 응답 전후 수신과 연결 종료를 관측 |
| 실행 위치 | GCP 백엔드 A/B, 공용 Redis, OpenSQL 3노드·OpenProxy, 별도 GCP 부하 VM |
| 코드 기준 | 앱 `9cd1c10b8d1d14fdc70184ee08e974905e5c8880`; 계측기의 `--count-only` 회수 모드는 이 run 전에 로컬 단위 시험 6/6 통과 |
| 절차·명령 | A 발행/B 미발행 fixture; 부하 VM `websocket_dashboard_revocation.py --count-only --clients 50 --clients-b 25 --before-seconds 5 --after-seconds 10` 및 실제 `DELETE /admin/users/{시험 사용자}/roles/ADMIN` |
| 성공 조건 | 회수 전 A/B 각각 실제 메시지 수신, 회수 API 200, 응답 후 추가 수신 여부·연결 종료 수를 숨기지 않고 기록 |
| 관측 시간 | 2026-10-02 14:20:40–14:20:55 KST; 회수 API 응답 14:20:45.420908 KST |
| 수신 결과 | 구독 50/50(A 25, B 25). 회수 전 A **450건**, B **300건**. 응답 후 관측된 추가 수신 A **0건**, B **0건**. 연결 종료 이벤트 50건(`ConnectionClosedError`) |
| 서버 이벤트 | 전체 fixture 실행 중 A 발행 성공 388회, B `미발행` 464회; 각 서버 최대 인증 세션 25개 |
| 종료·해석 | A/B fixture 종료 코드 0, 양쪽 시험 계정 각 2개 정리. **이 관측 창에서는** 회수 응답 후 수신이 없었다. B의 실제 DB 요약에는 발행·인가 시각이 없으므로 이 수치만으로 모든 경합에서 `200 이후 새 권한 승인 0건`을 증명한다고 확대하지 않는다 |

원본: [클라이언트 이벤트](client-events.jsonl), [A 발행 이벤트](A-publisher.tsv), [B 미발행 이벤트](B-publisher.tsv). 클라이언트 파일에는 회수 HTTP 상태와 시각은 있지만 URL·JWT·사용자 ID는 없다.
