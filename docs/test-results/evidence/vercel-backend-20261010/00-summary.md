# Vercel–GCP 백엔드 연결 검증 요약

이 문서는 서로 다른 목적·반복 실행을 합산해 성공률을 부풀리지 않고, 각 실행 기록의 위치를 연결한다. 실행 당시의 원본 콘솔 스트림을 별도 파일로 실시간 보존하지는 못했다. 아래 파일은 명령 결과와 상태 코드를 확인한 뒤 비식별 요약으로 작성했으며, 초 단위 실행 시각이 없는 항목은 날짜만 기록했다.

| 구분 | 실행 ID | 결과 | 개별 기록 |
| --- | --- | --- | --- |
| 로컬 게이트웨이 빌드 | `vercel-backend-gateway-local-20261010-01` | Nginx 설정 통과; 초기 셸·샌드박스 오류 후 재실행 | [01](01-local-gateway-build.md) |
| Cloud Run 첫 빌드 | `vercel-backend-cloud-run-build-20261010-01` | 소스 읽기 권한 403으로 실패 | [02](02-cloud-run-initial-build-failure.md) |
| Cloud Run 재배포 | `vercel-backend-cloud-run-20261010-02` | 일반 API 200, 인증 필요 401, 차단 경로 404, LB 2/2 | [03](03-cloud-run-gateway.md) |
| Vercel HTTP | `vercel-backend-http-20261010-01` | 화면·공개 API 200, 인증 필요 401 | [04](04-vercel-http.md) |
| WebSocket 수정 전 | `vercel-backend-wss-20261010-before` | Origin 검사 403 | [05](05-websocket-before-origin-fix.md) |
| Java 단위 시험 | `vercel-backend-java-unit-20261010-02` | 70/70 통과 | [06](06-java-unit.md) |
| 통합 시험 첫 실행 | `vercel-backend-integration-20261010-01` | PostgreSQL 연결 거부로 4/4 미통과 | [07](07-integration-db-unavailable.md) |
| 격리 DB 통합 재실행 | `vercel-backend-integration-20261010-02` | 4/4 통과 | [08](08-integration-isolated-db.md) |
| 앱 A 순차 배포 | `vercel-backend-app-a-20261010-01` | health 200, loopback WS 101 | [09](09-app-a-rollout.md) |
| 앱 B 순차 배포 | `vercel-backend-app-b-20261010-01` | health 200, loopback WS 101, LB 2/2 | [10](10-app-b-rollout.md) |
| 공개 WebSocket 재시험 | `vercel-backend-wss-20261010-after` | Upgrade 101, 5/5 | [11](11-public-websocket-after-fix.md) |
| 임시 접근 정리 | `vercel-backend-cleanup-20261010-01` | 임시 키·빌드 역할 잔여 0, 배포 파일 제거 | [12](12-access-cleanup.md) |

## 판정 경계

- 확인: 공개 프런트의 HTTP API proxy와 공개 WebSocket 핸드셰이크가 기존 A/B 백엔드에 도달한다.
- 미확인: 정상 계정 로그인, STOMP 인증·구독·메시지 수신, PDF 업로드·GCS·Worker·검색, 큰 요청 본문. 이를 사용자 기능 E2E 완료로 표현하지 않는다.
- 유지 자원: 시연용 Cloud Run 게이트웨이, 기존 내부 LB, A/B 앱. 임시 빌드 권한과 OS Login SSH 키는 회수했다.
