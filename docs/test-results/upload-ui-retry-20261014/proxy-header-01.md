# UI-20261014-05 · 업로드 프록시 헤더·본문 전달

- 기록 시각: 2026-10-14 01:35 KST
- 실행 위치: 로컬 `frontend/`, 모의 `fetch` 응답 사용
- 방법: `node --experimental-strip-types --input-type=module`에서 프록시 `POST`를 직접 호출하고 모의 백엔드 호출 인자를 검사
- 입력: `Idempotency-Key: upload-check-123`, 제목과 10바이트 예시 파일을 담은 multipart 요청 1건
- 성공 기준: 백엔드로 보내는 키 일치, multipart 형식·비어 있지 않은 본문 유지, 모의 HTTP 201 응답 전달
- 관측: 키 일치, 전달 본문 284바이트, 응답 201; 단언 4개 모두 통과
- 판정: 통과. 실제 네트워크·백엔드 저장 동작은 검증하지 않음
