# Vercel 산출물 로컬 HTTP 미리보기

- 실행 ID: `vercel-preview-20261010-01`
- 시각: 2026-10-10 KST (정확한 시작·종료 시각 미수집)
- 위치: 로컬 격리 작업 트리의 `frontend/`, 루프백 포트 4174
- 목적·기준: Vercel preset 미리보기의 `/`가 HTTP 200으로 응답하고 DocGrid HTML·CSS를 포함
- 절차: `NITRO_PRESET=vercel npx vite preview --config vite.config.vercel.ts --host 127.0.0.1 --port 4174` → `curl`로 `/` 요청 → 서버 종료
- 관측: 첫 `curl`은 샌드박스 네트워크 경계로 연결 실패(종료 코드 7). 같은 호스트 권한으로 재시도하자 HTTP 200, `text/html`, 11,018바이트, 0.043719초. HTML에서 `DocGrid`와 CSS 참조 확인.
- 해석: 로컬 렌더링 통과. Vercel URL, 공개 API 및 WebSocket 동작은 미검증.
- 정리: 로컬 미리보기 프로세스 종료.
- 증거 한계: 원문 전체를 별도 파일로 기록하지 않았다.
