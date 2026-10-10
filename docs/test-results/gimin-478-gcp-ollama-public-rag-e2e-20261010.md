# GCP 7B Ollama 공개 PDF·RAG E2E 검증

## 결론

GCP 전용 CPU VM에서 Qwen 2.5 7B를 띄우고 앱 A/B의 내부 연결을 확인했다. 공개 API에서 합성 PDF 업로드·인덱싱·검색·비동기 답변 생성은 **1회 E2E 통과**했다. 답변은 384자와 인용 3건으로 반환됐다. 7B CPU 추론은 문맥 길이에 따라 60초를 넘었으므로 앱의 생성 제한과 HTTP 읽기 제한을 각각 180초·210초로 올린 시험 설정을 사용했다. 이는 처리량이나 모든 질문의 완료 시간을 보장하지 않는다.

```text
공개 요청에서 확인한 경로

시험 클라이언트 → Vercel /api/backend → 공개 GCP API 진입점
                                      → 앱 A/B → OpenProxy → OpenSQL
                                              ├─ GCS 원본 PDF
                                              ├─ BGE-M3 임베딩
                                              └─ Ollama CPU VM의 qwen2.5:7b
  업로드 201 → 시험 버전 Worker INDEXED → 검색 결과 4건
             → RAG PROCESSING → SUCCESS(답변 384자·인용 3건)
```

```text
CPU 모델 제한시간의 근거

기존 60초 시점: 첫 토큰 36.999초, done=false → 완료로 판정 불가
확장된 짧은 합성 입력: 첫 토큰 24.968초 → 총 41.941초, done=true
확장된 2,110자 입력: 첫 토큰 96.941초 → 총 121.397초, done=true
실제 공개 RAG: 검색 뒤 102.5초에 SUCCESS 관측

서로 다른 단건 시험이므로 위 값을 p95나 SLA로 읽지 않는다.
```

```text
Worker 재시도와 최종 판정

PDF 업로드·Job 생성
  ├─ 시도 1: BGE 요청 시간 초과
  ├─ 시도 2: BGE 대기열 제한(HTTP 429)
  └─ 시도 3: 성공 → INDEXED
검색 4건 → Ollama 답변 완료 → 인용 3건

중간 실패 2회를 지우지 않고 최종 상태와 분리한다.
```

| 실행 ID | 위치·방법 | 핵심 결과 | 해석·증거 |
| --- | --- | --- | --- |
| `rag478-ollama-short-stream-r1` | Ollama VM `/api/generate`, 465자 합성 입력, 60초 관측 | 첫 토큰 36.999초, 60.027초에도 미완료 | 60초 제한 부족. [실행 요약](evidence/issue-478/rag478-ollama-short-stream-r1.md) |
| `rag478-ollama-7b-extended-r1` | Ollama VM, 짧은 합성 입력, 확장 제한 | 총 41.941초, `done=true` | 확장 제한 내 완료. [실행 요약](evidence/issue-478/rag478-ollama-7b-extended-r1.md) |
| `rag478-ollama-7b-3200-r1` | Ollama VM, 실제 2,110자 합성 입력 | 총 121.397초, `done=true` | 장문 단건 완료. [실행 요약](evidence/issue-478/rag478-ollama-7b-context-r1.md) |
| `rag478-public-e2e-r1` | 공개 HTTP, PDF→Worker→검색→RAG | PDF 201, INDEXED, 검색 4건, 답변 384자·인용 3건 | E2E 통과. [실행 요약](evidence/issue-478/rag478-public-e2e-r1.md) |

탐색 중 실패와 모델 비교도 실행별로 분리했다: [초기 짧은 요청](evidence/issue-478/rag478-ollama-cold-r1.md), [첫 장문 180초 시간 초과](evidence/issue-478/rag478-ollama-context-r1.md), [장문 스트림 90초 시간 초과](evidence/issue-478/rag478-ollama-stream-r1.md), [3B 비교 실행](evidence/issue-478/rag478-ollama-3b-short-stream-r1.md). 후속 재시도 성공으로 앞선 실패를 지우지 않는다.

## 범위·운영 상태

- 앱 A/B는 Ollama 내부 주소와 `qwen2.5:7b`를 사용했고 양쪽에서 API 연결 HTTP 200을 확인했다. Ollama VM에는 외부 IP를 남기지 않았다.
- 공개 화면의 브라우저 렌더링과 인증 WebSocket은 이 실행에서 다시 검증하지 않았다. **공개 HTTP E2E**와 화면 E2E를 혼동하지 않는다.
- 합성 시험 계정은 생성됐지만 비밀번호·JWT는 보존하지 않아 재로그인할 수 없다. 테스트 문서와 결과 행은 시험 DB에 남는다.
- 시험 완료 뒤 전용 Ollama VM은 `TERMINATED`로 중지했다. BGE VM도 시험 전 상태인 `TERMINATED`로 되돌렸다. 앱 A/B, 부하 VM, 관측 VM은 다시 실행 중이며 앱 A/B의 HTTP 200과 Worker 비활성을 확인했다. 현재 공개 RAG를 다시 시연하려면 모델 VM을 재시작해야 한다.
- 후속 지표 시험 `rag479-live-034630`은 새 앱 JAR에서 실행됐으며 별도 이슈의 결과다. 본 문서의 384자 답변을 지표 적용 후의 답변으로 소급하지 않는다.
