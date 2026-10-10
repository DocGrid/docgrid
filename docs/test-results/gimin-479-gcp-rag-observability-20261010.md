# GCP BGE-M3·Ollama RAG 관측 지표 연결

## 결론

BGE-M3의 실제 `/metrics`를 GCP 관측 VM Prometheus가 수집했고, 앱 A/B에 Ollama 호출 결과·시간·토큰 지표를 배포했다. 공개 PDF→검색→RAG 실요청 중 BGE 요청 **2건**, Ollama `partial` **1건**·**177.658711562초**·출력 토큰 **350개**를 Prometheus와 Grafana 인증 자료원에서 대조했다. 지표를 읽을 수 있는 경계는 앱→BGE `/health` **200**, 앱→BGE `/metrics` **403**, 관측 VM→BGE `/metrics` **200**으로 확인했다.

이번 GCP 실요청의 답변은 `SUCCESS`와 인용 3건이었지만 **부분 답변**으로 분류됐고 시험 정답 마커도 포함하지 않았다. 답변 상태만으로 의미적 정답이나 7B의 충분한 성능을 주장하지 않는다. 이전 지표 적용 전 공개 E2E의 완전 답변은 [별도 실행](gimin-478-gcp-ollama-public-rag-e2e-20261010.md)으로 구분한다.

```text
그림 1. 실제 수집 경로

공개 PDF·검색 요청 → 앱 A → BGE-M3 :8000 /embed
                    │           └─ 요청 Counter·대기열·메모리 지표
                    │
                    └─ Ollama :11434 /api/generate
                          └─ 앱 OllamaClient가 결과·시간·토큰 Micrometer 기록

관측 VM Prometheus ── /metrics ──▶ BGE-M3
                └── /actuator/prometheus ──▶ 앱 A/B
                         │
                         ▼
                 Grafana 인증 자료원 질의
```

```text
그림 2. 같은 포트의 보안 경계

앱 A/B ── TCP 8000 ──▶ BGE /embed  : 허용
                  └──▶ BGE /health : 200 관측
                  └──▶ BGE /metrics: 403 관측

관측 VM 태그 ── 좁은 방화벽 허용 ── TCP 8000 ──▶ BGE /metrics: 200 관측
그 밖의 VM ── 기존 BGE ingress deny ───────────▶ 포트 차단

방화벽은 URL 경로를 모르므로, /metrics의 최종 판정은
BGE 앱이 EMBEDDING_METRICS_ALLOWED_CLIENTS와 실제 클라이언트 주소를 비교한다.
```

```text
그림 3. OllamaClient 내부 계측 순서

RagFacade → OllamaClient.generate(prompt)
               │ 시작 단조 시계 기록
               ├─ /api/generate 스트림 정상 종료 → complete
               ├─ 답변 잘림 안내와 함께 반환       → partial
               └─ 예외                         → failure
               │
               └─ finally: 결과 Counter + 소요 시간 Timer
                    └─ 응답에 실제 토큰 수가 있을 때만 input/output Summary

레이블: outcome·kind만 사용.
프롬프트·답변·사용자·문서 ID는 메트릭에 기록하지 않는다.
```

```text
그림 4. 시험과 원복의 시간 경계

사전: 앱 A/B 실행, 관측·부하 VM 중지, BGE 중지
시험: BGE·Ollama 실행 → 앱 JAR 배포 → 앱 B 임시 중지
      → 관측 VM 시작 → BGE scrape up=1 → 합성 PDF·RAG
      → Grafana 수치 대조
원복: Ollama·BGE 중지 → 앱 B·부하 VM 재시작
      → 앱 A Worker 필터·시험 프로필 제거 → 앱 A/B HTTP 200
      → OS Login·VM 메타데이터 임시 키 삭제

현재 BGE는 의도적으로 중지됐으므로 Grafana의 최신 up은 0이 될 수 있다.
시험 중의 up=1과 현재 상태를 혼동하지 않는다.
```

## 변경 내용

| 파일·구성요소 | 변경 목적 |
| --- | --- |
| `backend/src/main/java/com/opensource/docgrid/domain/rag/service/OllamaClient.java` | 호출 결과 `complete/partial/failure`, duration, 실제 응답 토큰 수를 저카디널리티 지표로 기록 |
| `backend/src/test/java/com/opensource/docgrid/domain/rag/service/OllamaClientTest.java`·`OllamaClientWireContractTest.java` | 세 결과 분기와 토큰 기록을 결정적 계약 테스트로 확인 |
| `backend/embedding-server/main.py`·`test_main.py` | 배포 설정이 있을 때 `/metrics`를 지정 클라이언트만 허용; `/embed`는 그대로 유지 |
| `monitoring/prometheus/prometheus.yml`·`targets/embedding-provider.yml` | 로컬 기본 BGE 대상과 외부 배포의 target 파일을 분리 |
| GCP 관측 VM Prometheus 설정·방화벽 | 기존 구성 백업 뒤 실제 BGE 대상·관측 태그 ingress만 추가, 문법 검사 후 적용 |

## 실행·검증

| 실행 ID | 위치·명령 또는 방법 | 관측 결과 | 판정·상세 |
| --- | --- | --- | --- |
| `rag479-local-contract-r1` | 로컬 Gradle 대상 테스트, BGE 이미지 안 pytest, promtool config | Java **16/16**, Python **23/23**, promtool 통과 | [계약 로그](evidence/issue-479/rag479-local-contract-r1.md) |
| `rag479-gcp-scrape-r1` | GCP 앱·BGE·관측 VM에서 HTTP 상태, Prometheus·Grafana query | 200/403/200 접근 경계, `up=1`, BGE 요청 **2** | [수집 로그](evidence/issue-479/rag479-gcp-scrape-r1.md) |
| `rag479-live-034630` | 공개 API PDF 1건·검색·RAG + 지표 대조 | `INDEXED`, 검색 4건, 답변 69자·인용 3건; Ollama `partial=1`, **177.659초**, output **350토큰** | [E2E·지표 로그](evidence/issue-479/rag479-live-034630.md) |

## 해석과 남는 범위

- 앱 A/B의 적용 JAR SHA-256은 동일하다. 두 앱의 공개 `/departments`가 **각각 200**이고 Worker가 **둘 다 비활성**인 상태로 끝났다.
- 첫 GCP 지표 시험의 Worker 설정은 필수 `worker-scope-test` 프로필 누락으로 기동에 실패했다. 반복된 공개 503을 서비스 실패 표본으로 남기고 원인을 보완한 뒤 동일 문서 버전만 다시 처리했다.
- Grafana 값은 인증 API의 Prometheus 자료원을 통한 **실제 질의**로 확인했다. 새 전용 대시보드 패널은 만들지 않았다.
- `complete`·`failure` 지표 분기는 로컬 테스트에서 확인했고, GCP 실요청에서는 `partial`만 발생했다. 이 차이를 성공·실패 집계에 숨기지 않는다.
- 관측 VM은 시험 전 실행 상태로 복귀했고 BGE·Ollama는 중지했다. 따라서 지금 공개 RAG 질문은 Ollama VM을 재시작하지 않으면 성공하지 않는다. CPU VM의 중지 후에도 부팅 디스크 보관 요금은 남는다.
