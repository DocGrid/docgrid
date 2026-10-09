# pdf469-result-r1 — 공개 검색·다운로드 결과

- 실행 시각: 2026-10-10 KST, 약 05:03–05:05. 원본 stdout에 초 단위 시각이 없어 근사 구간이다.
- 실행 위치: 로컬 시험 클라이언트 → 공개 Vercel `/api/backend` → GCP 앱 A/B → OpenSQL·GCS·CPU 임베딩 서버.
- 목적: Worker의 `INDEXED` 표시뿐 아니라 실제 사용자 API의 벡터 검색과 원본 다운로드를 확인한다.
- 절차: 시험 계정 JWT를 프로세스 메모리에서만 요청 헤더에 넣어 `POST /search`에 합성 PDF의 구분 문구를 질의하고, `GET /api/documents/114/status`, `GET /api/documents/114/file?disposition=attachment`로 상태·파일을 대조했다. 비밀번호·JWT·응답 본문은 로그에 쓰지 않았다.
- 성공 기준: 검색 HTTP 200, 시험 문서 포함, 다운로드 HTTP 200과 업로드 원본 SHA-256 일치.

| 항목 | 관측값 | 판정 |
| --- | ---: | --- |
| 문서 상태 API | HTTP 200, 문서·버전 `INDEXED` | 통과 |
| 벡터 검색 | HTTP 200, 총 결과 2건 중 시험 문서 114가 1건 | 통과 |
| 검색 응답의 RAG | `PROCESSING` | 검색 완료와 RAG 답변 완료는 별개. 답변 완료는 이 실행에서 미검증 |
| 원본 다운로드 | HTTP 200, 17,208 bytes | 통과 |
| 다운로드 SHA-256 | `47a00b67370c35ca8327900ecf01c179b902d1e09d5f10e33c9c640d3f6c903b` | 업로드 원본과 일치 |

검색 응답은 2건이므로 이 시험은 다른 사용자 데이터의 내용이나 검색 순위를 외부 문서에 공개하지 않는다. UI의 클릭·화면 렌더링과 인증된 WebSocket은 별도 실행 대상이다.
