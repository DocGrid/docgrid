# 공개 프런트의 백엔드 연결 경계 확인

- 실행 ID: `vercel-api-boundary-20261010-01`
- 시각: 2026-10-10 약 01:12 KST
- 위치: 로컬 클라이언트 → Vercel 공개 API 프록시, Vercel 프로젝트 설정 읽기
- 목적·기준: 프런트 배포만으로 기존 GCP 내부 백엔드에 연결되는지 확인. 연결되지 않으면 사용자 기능을 성공으로 판정하지 않음.
- 명령·절차: `curl`로 `GET /api/backend/auth/me` 상태 확인; Vercel 프로젝트의 환경변수 목록 확인; `app/api/backend/[...path]/route.ts`와 WebSocket URL 기본값 확인
- 관측: API 프록시가 502 `application/json` 응답. 프로젝트 설정에 환경변수 없음. 코드상 `BACKEND_API_URL` 미설정 시 프록시는 `http://localhost:8080`을 사용하며, WebSocket도 `NEXT_PUBLIC_BACKEND_WS_URL` 미설정 시 localhost를 사용함.
- 해석: 프런트 화면은 공개 배포됐지만 로그인·업로드·검색·WebSocket은 GCP 백엔드로 연결되지 않음. 502의 구체적인 런타임 원인은 별도 함수 로그로 확인하지 않았으므로, 기본 URL과 미설정 상태는 코드·설정 기반 원인 추론으로 분리함.
- 다음 관문: 공개 HTTPS 백엔드 진입점 마련 후 두 URL 설정과 E2E 재검증. 기존 내부 LB 주소를 Vercel에 입력하는 것으로 해결된다고 가정하지 않음.
