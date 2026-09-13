# DocGrid

> 팀의 지식에서 권한을 지키며 근거가 포함된 답을 찾는 오픈소스 문서 검색 워크스페이스

DocGrid는 조직에 흩어진 PDF·DOCX 문서를 자동으로 인덱싱하고, 사용자가 열람할 수 있는 문서만
벡터 검색과 RAG 답변에 활용합니다. 문서·컬렉션 관리부터 인용 근거, MCP 연동, 인덱싱 Worker와
복구 상태 관측까지 하나의 웹 인터페이스에서 제공합니다.

## 주요 기능

- **문서 인덱싱 파이프라인**: PDF·DOCX 원본을 저장하고 본문 Parsing, Chunk 분할, BGE-M3 Batch
  Embedding, pgvector 저장을 자동으로 처리합니다.
- **권한 기반 AI 검색**: 사용자·역할·부서와 문서·컬렉션 공개 범위를 검색 전에 적용해 접근 가능한
  문서만 의미 검색합니다.
- **근거가 남는 RAG 답변**: Ollama로 답변을 생성하고 사용한 문서와 Chunk를 인용 근거로 함께 저장하고
  표시합니다.
- **지식 워크스페이스 관리**: 문서 Version, 원본 미리보기·다운로드, 계층형 컬렉션, 직접 권한 부여·회수를
  웹에서 관리합니다.
- **MCP 연동**: 전용 Access Token으로 문서 검색·상세 조회·인덱싱 상태 도구를 제공하고 Rate Limit과
  출력 Sanitization을 적용합니다.
- **RAGOps 운영 화면**: WebSocket으로 Job·Worker 상태를 갱신하고 실패 원인, Retry, Queue·동기화 상태를
  관측하고 복구할 수 있습니다.

## 아키텍처

![DocGrid 아키텍처](docs/images/architecture.png)

1. API가 업로드 원본을 파일 저장소에 보관하고 문서 Metadata와 인덱싱 Job을 DB에 기록합니다.
2. Indexing Worker가 원본을 Parsing·Chunking하고 BGE-M3 Embedding을 pgvector에 저장합니다.
3. 검색 요청은 Query Embedding과 권한 Pre-filter를 적용해 접근 가능한 문서의 Chunk를 조회합니다.
4. RAG Worker가 검색 문맥으로 답변을 생성하고 인용 근거를 저장한 뒤 WebSocket으로 결과를 전달합니다.

## 기술 스택

| 영역 | 기술 |
|---|---|
| Backend | Java 17, Spring Boot 3.5.16, Spring Security, Spring Data JPA, Spring AI MCP, WebSocket, Flyway |
| Frontend | React 19, TypeScript 5, Tailwind CSS 4, Vinext, Vite |
| Database | OpenSQL 17.8, PostgreSQL 17, pgvector 0.8.1 |
| AI | BAAI/bge-m3, Ollama, qwen2.5:7b |
| Storage | Local Filesystem, MinIO, AWS S3 |
| Cache | Redis 7 |
| Observability | RAGOps Dashboard, Prometheus |
| Test | JUnit 5, Gradle, Node.js Test Runner |

## 빠른 실행

아래는 별도 EC2 접근 권한 없이 로컬 PostgreSQL과 MinIO로 핵심 기능을 확인하는 경로입니다. 명령은
저장소 루트에서 실행합니다.

### 1. 사전 준비

- Git
- Java 17
- Node.js 22.13.0 이상
- Docker Desktop과 Docker Compose
- RAG 답변까지 확인하려면 Native Ollama

### 2. Clone과 환경 변수

```bash
git clone https://github.com/DocGrid/docgrid.git
cd docgrid
cp .env.example .env
cp frontend/.env.example frontend/.env.local
npm --prefix frontend install
```

`.env.example`은 로컬 PostgreSQL, MinIO와 자동 인덱싱 Worker를 실행할 수 있는 기본값을 제공합니다.
실제 비밀번호와 외부 서버 인증정보는 `.env`, README, Issue 또는 Commit에 기록하지 않습니다.

### 3. PostgreSQL, MinIO, Redis, BGE-M3 실행

```bash
docker compose up -d --build postgres minio redis embedding-server
docker compose logs -f embedding-server
```

BGE-M3는 첫 실행 시 약 3GB 모델을 내려받으므로 준비까지 10~15분 정도 걸릴 수 있습니다. 준비 완료 Log를
확인한 뒤 `Ctrl+C`로 Log 조회만 종료하고 Health Check를 실행합니다.

```bash
curl -f http://localhost:8000/health/ready
curl -f http://localhost:9000/minio/health/live
```

### 4. Ollama 실행

최종 RAG 답변 생성에는 `qwen2.5:7b`가 필요합니다. Ollama를 운영체제에 맞게 Native로 설치한 뒤
모델을 준비합니다. macOS에서는 다음 명령을 사용할 수 있습니다.

```bash
brew install ollama
brew services start ollama
ollama pull qwen2.5:7b
ollama run qwen2.5:7b "안녕"
```

초기 설치 후에는 Redis, BGE-M3, Ollama를 전용 스크립트로 한 번에 기동하고 실제 연결 상태까지 확인합니다.

```bash
./scripts/local-services.sh start
./scripts/local-services.sh status
```

`start`는 BGE-M3 준비 완료까지 최대 15분 기다리며, 실패하면 서비스별 복구 명령을 출력합니다.

Apple Silicon에서는 `ollama ps`의 `PROCESSOR`가 GPU인지 확인합니다. 설치와 성능 관련 상세 내용은
[백엔드 실행 문서](backend/README.md#ollama-rag-llm-서버)를 참고하세요.

### 5. Backend와 Frontend 실행

각 명령을 별도 Terminal에서 실행합니다.

```bash
./backend/gradlew -p backend bootRun
```

```bash
npm --prefix frontend run dev
```

- Web: `http://localhost:3000`
- API 문서: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Backend와 Embedding Provider의 운영 메트릭을 함께 수집하려면 선택형 monitoring profile을 실행합니다.

```bash
docker compose --profile monitoring up -d prometheus
```

- Prometheus Target: `http://localhost:9090/targets`
- 설정과 기존 Prometheus 연결: [monitoring/prometheus/README.md](monitoring/prometheus/README.md)

### 6. 핵심 흐름 확인

1. 회원가입 또는 로그인
2. PDF/DOCX 문서 업로드
3. 문서 상태가 `PENDING → PROCESSING → INDEXED`로 바뀌는지 확인
4. 문서 내용으로 AI 검색
5. RAG 답변과 인용 근거 확인

### 종료

```bash
docker compose stop postgres minio redis embedding-server
# macOS에서 Homebrew로 Ollama를 실행한 경우
brew services stop ollama
```

`docker compose down -v`는 DB·Object·모델 Cache Volume을 삭제할 수 있으므로 일반적인 종료에는 사용하지
않습니다.

## 환경 선택

파일 저장소는 `STORAGE_TYPE`으로 하나만 선택합니다. API와 Worker는 반드시 같은 저장소 설정을 사용해야
하며, 저장소 변경은 기존 파일을 자동으로 이전하지 않습니다.

| `STORAGE_TYPE` | 용도 | 주요 설정 |
|---|---|---|
| `local` | 별도 Object Storage 없는 단일 실행 환경 | `STORAGE_LOCAL_ROOT`, `STORAGE_BUCKET` |
| `minio` | 기본 Docker 개발 환경 | `MINIO_ENDPOINT`, Credential, `STORAGE_BUCKET` |
| `s3` | 공용 개발·배포 환경 | `AWS_REGION`, AWS Credential, `STORAGE_BUCKET` |

OpenSQL 개발 DB는 SSH Tunnel을 통해 사용할 수 있습니다. 공급사 설치 파일, License, DB Credential과
SSH Key는 저장소에 포함하지 않습니다. 환경별 설정은 [.env.example](.env.example),
[로컬 DB 실행 문서](docs/local-db.md)와
[OpenSQL 검증 Runbook](docs/test-results/gimin-%23124-opensql-verification-runbook.md)을 참고하세요.

## 테스트

일반 회귀 테스트는 외부 장시간 Benchmark와 실제 인프라 E2E를 제외하지만 PostgreSQL과 Redis를
사용하는 통합 테스트를 포함합니다. 두 Service를 먼저 준비합니다.

```bash
docker compose up -d --wait postgres redis
./backend/gradlew -p backend test
npm --prefix frontend test
```

파일 저장소 Adapter와 실제 자동 Worker 전체 흐름은 별도 E2E로 검증합니다.

```bash
docker compose up -d --build --wait postgres minio redis embedding-server

DB_HOST=127.0.0.1 DB_PORT=55432 \
  ./backend/gradlew -p backend storageWorkerE2eTest
```

## 검증 결과

아래 수치는 서로 다른 고정 환경에서 측정한 재현 가능한 기준선이며 운영 SLO를 의미하지 않습니다.

| 검증 영역 | 결과 |
|---|---|
| PDF·DOCX 전체 E2E | 50·100문서 Profile에서 31.046·32.063문서/분 |
| Pipeline 정합성 | 네 번의 실행, 합계 300문서·1,200 Vector에서 실패·Retry·미완료·중복 0건 |
| OpenSQL Job Claim | Worker 5에서 748.45 TPS, p99 13.187ms |
| BGE-M3 Batch | Batch 32가 측정 최대 처리량의 98.27%, Batch 64보다 p95 46.89% 감소 |

상세 조건, 원본 데이터, 한계와 재현 명령은
[인덱싱·Vector 검색 최종 통합 성능 리포트](docs/test-results/gimin-%23145-final-performance-report.md)에
기록되어 있습니다.

## 문서

| 문서 | 내용 |
|---|---|
| [Backend README](backend/README.md) | PostgreSQL, BGE-M3, Ollama 실행 |
| [Frontend README](frontend/README.md) | Web 실행, 인증과 API 연결 범위 |
| [로컬 DB 실행](docs/local-db.md) | PostgreSQL 17 + pgvector 로컬 기준선 |
| [OpenSQL 호환성·성능](docs/test-results/gimin-%23124-opensql-compatibility-performance.md) | OpenSQL 17.8 기능·성능 검증 |
| [최종 성능 리포트](docs/test-results/gimin-%23145-final-performance-report.md) | 인덱싱·검색 Benchmark 통합 결과 |
| [검색·RAG E2E](docs/test-results/kangcheolung-%2378-search-rag-e2e-test.md) | 검색부터 답변·인용 저장까지 전체 흐름 |
| [MCP E2E](docs/test-results/kangcheolung-%23127-mcp-claude-desktop-e2e.md) | MCP Client 연동 검증 |
| [파일 저장소·Worker E2E](docs/test-results/Gimini-3-%23284-file-storage-worker-e2e.md) | Local·MinIO·S3 Adapter 전체 관통 검증 |

설계 의사결정은 [`docs/design/`](docs/design/), 실행된 Test Plan과 결과는
[`docs/test-results/`](docs/test-results/)에서 확인할 수 있습니다.

## 저장소 구조

```text
.
├── backend/          # Spring Boot API, Worker와 BGE-M3 서버
├── frontend/         # DocGrid Web Application
├── docs/design/      # 설계 의사결정
├── docs/test-results/# 실행된 Test Plan, 측정과 결과
├── docker/           # 로컬 인프라 초기화
├── monitoring/       # Prometheus 설정과 Alert Rule
├── scripts/          # 검증·보고 자동화
└── docker-compose.yml
```

## 라이선스 및 외부 구성요소

DocGrid의 자체 소스코드와 문서는 [Apache License 2.0](LICENSE)에 따라 배포합니다.

| 외부 구성요소 | 사용 방식 | 라이선스 경계 |
|---|---|---|
| [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3) | Embedding Server 첫 실행 시 다운로드 | MIT License |
| [qwen2.5:7b](https://ollama.com/library/qwen2.5:7b) | 사용자가 `ollama pull`로 다운로드 | Apache License 2.0 |
| OpenSQL | 외부 개발·검증 DB로 연결 | 공급사 배포본과 License는 저장소에 포함하지 않음 |
| Java·npm 의존성과 Container Image | Build 또는 실행 시 외부 Registry에서 획득 | 각 구성요소의 별도 라이선스 적용 |

외부 모델, 라이브러리, Container Image와 OpenSQL 배포본은 DocGrid의 Apache License 2.0 적용 범위에
포함되지 않습니다.
