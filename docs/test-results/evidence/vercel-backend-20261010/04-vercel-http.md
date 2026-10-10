# Vercel 프런트엔드에서 백엔드 HTTP 연결

- 실행 ID: `vercel-backend-http-20261010-01`
- 실행 시각: 2026-10-10 KST. 최종 상태는 배포·백엔드 교체 후 재조회했다.
- 위치·대상: 공개 Vercel 프런트엔드 → 프런트 API proxy → Cloud Run 게이트웨이 → GCP 내부 LB → 앱 A/B.
- 목적·통과 기준: 화면과 API proxy가 열리고, 비인증·차단 경로가 예상 응답을 돌려준다.
- 명령·절차: Vercel Production 환경에 `BACKEND_API_URL`·`NEXT_PUBLIC_BACKEND_WS_URL`을 설정하고 `vercel deploy --prod` 실행. 이후 `curl`로 각 경로의 상태를 확인했다.
- 관측: `/` 200, `/login` 200, `/api/backend/departments` 200, `/api/backend/auth/me` 401, `/api/backend/v3/api-docs` 404. 존재하지 않는 로그인 자격증명으로 보낸 요청은 401. 공개 번들 5개 중 1개에 새 WebSocket 게이트웨이 주소가 반영됨을 확인했다.
- 해석: 프런트 화면·API proxy·실제 백엔드까지 연결됐다. 401은 인증 실패이지 게이트웨이 연결 실패가 아니다.
- 한계: 정상 사용자로 로그인하거나 PDF 업로드·검색을 마친 E2E 검증은 아직 하지 않았다. 환경변수의 실제 URL 및 클라우드 식별자는 보존하지 않았다.
