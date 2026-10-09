# `475-vercel-01` — 병합 프런트 Production 배포

- 목적: 공개 화면에 RAG STOMP heartbeat를 포함한 병합 소스 반영
- 위치·시각: Vercel 원격 빌드, 2026-10-10 약 06:08 KST. 시작 초 단위 시각은 별도 수집하지 못했다.
- 소스: `04c9b0c`
- 방법: 기존 프로젝트 연결 후 `vercel deploy --prod --yes`; 비밀값을 명령 인자로 전달하지 않았다.
- 성공 기준: 원격 빌드 READY, 기존 Production alias, 공개 페이지 HTTP 200, RAG 번들에 heartbeat 코드
- 원본 요약: `build:vercel` → Nitro/Vite 5개 단계 완료 → `readyState=READY` → 기존 Production alias. 공개 `/search` 200, 7개 JS asset 중 RAG 포함 1개, 해당 asset에서 10초 타이머 검출.
- 해석: 새 프런트가 배포됐다. 정적 번들 검사는 장시간 소켓 heartbeat 수신을 증명하지는 않는다. 배포 연결 과정에서 생성된 로컬 임시 OIDC 설정 파일은 값 출력 없이 삭제했다.
