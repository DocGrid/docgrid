# 공개 프런트 경로·정적 자산 확인

- 실행 ID: `vercel-route-smoke-20261010-01`
- 시각: 2026-10-10 약 01:12 KST
- 위치: 로컬 클라이언트 → Vercel 공개 프로덕션 URL, Chrome 시각 확인
- 목적·기준: 첫 화면·직접 URL 진입·CSS가 HTTPS 200으로 응답하고 로그인 UI가 렌더링되는지 확인
- 명령·절차: `curl`로 `/`, `/search`, `/_next/static/css/index.JKlXiGH5.css`의 상태·형식·전송 크기를 확인하고 Chrome으로 `/` 접속
- 관측: `/` 200 HTML, `/search` 200 HTML, CSS 200 `text/css`·71,967바이트. 브라우저는 세션 확인 후 `/login?returnTo=%2Fsearch`로 이동했고 DocGrid 로그인 화면·입력란·버튼이 표시됨.
- 해석: 공개 정적 자산과 서버 렌더링 화면은 작동함. 인증된 사용자 흐름이나 DB·GCS 연결 성공을 의미하지 않음.
- 증거 한계: 세 경로와 비인증 화면만 확인했으며 모든 화면·브라우저·기기를 검증하지 않음.
