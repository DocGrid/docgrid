# DocGrid

> 팀의 지식에서 권한을 지키며 근거가 포함된 답을 찾는 오픈소스 문서 검색 워크스페이스

DocGrid는 조직의 PDF·DOCX 문서를 저장·인덱싱하고, 사용자가 열람할 수 있는 문서만 벡터 검색과
RAG 답변에 활용합니다. 문서·컬렉션 관리, 인용 근거, MCP 연동, 인덱싱 상태와 복구 작업을 웹에서
제공합니다. 로컬 개발은 PostgreSQL·MinIO·Valkey로 재현할 수 있습니다. 최종 배포 구성은
Vercel 프런트엔드, Cloud Run 공개 게이트웨이, GCP의 백엔드 A/B·AI·캐시 VM,
OpenProxy를 경유하는 OpenSQL 3노드와 GCS 원본 저장소를 연결합니다.

## 시연 영상

썸네일을 누르면 YouTube에서 재생됩니다.

| 1. 서비스 사용 흐름·인덱싱 장애 대응 | 2. OpenProxy A 장애 중 k6 부하·HTTP 응답 |
|---|---|
| [![서비스 사용 흐름 및 인덱싱 장애 대응 시연](https://img.youtube.com/vi/t2JHrvTSC-w/mqdefault.jpg)](https://youtu.be/t2JHrvTSC-w) | [![OpenProxy A 장애 중 k6 부하·HTTP 응답 시연](https://img.youtube.com/vi/hwP4-J86BqE/mqdefault.jpg)](https://youtu.be/hwP4-J86BqE) |
| **3. OpenProxy A 장애 중 실제 웹 기능** | **4. Primary VM 장애·Failover·멱등 재전송** |
| [![OpenProxy A 장애 중 실제 웹 기능 시연](https://img.youtube.com/vi/i9-LmozKqu4/mqdefault.jpg)](https://youtu.be/i9-LmozKqu4) | [![Primary VM 장애·Failover·멱등 재전송 시연](https://img.youtube.com/vi/NWlwwDB0a94/mqdefault.jpg)](https://youtu.be/NWlwwDB0a94) |

## 주요 기능

- **문서 인덱싱 파이프라인**: PDF·DOCX 원본을 저장하고 본문 Parsing, Chunk 분할, BGE-M3 Batch
  Embedding, pgvector 저장을 Worker가 처리합니다. Worker는 환경별로 명시적으로 켭니다.
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
- **환경별 저장소와 OpenSQL 연결**: 로컬·MinIO·GCS 저장소 Adapter와 OpenProxy A/B를 통한
  OpenSQL 3노드 연결을 지원합니다. AWS S3 Adapter도 코드에 남아 있지만 현재 GCP 시험 저장소는
  GCS입니다.

## 검증된 범위

아래 수치는 `docs/test-results`에 기록된 **실행별 관측값이며 보장이 아닙니다.** 행마다 환경·부하·실행
시점이 달라 서로 합산하거나 비교하지 않습니다. OpenSQL 장애 시험은 DB 밖 요청 원장의 `request_id`와
최종 primary의 DB 행을 실행 ID별로 대조했습니다.

| 검증 | 조건 (실행 ID·날짜) | 관측값 | 실패·한계 |
|---|---|---|---|
| [OpenProxy 한쪽 중단](docs/test-results/gimin-391-openproxy-ab-fault-load-20261003.md) | GCP, 100 req/s × 130초, A `ha391fa20001`·B `ha391fb20001`, 2026-10-03 | A 13,000건 중 201 **12,997**, B 13,001건 중 201 **12,993**. 201 응답의 DB 누락·중복 **0** | HTTP 500 A 3건·B 8건으로 **무오류 기준 실패**. 앞선 실행은 A 0건·B 10건 |
| [계획 switchover](docs/test-results/issue-412-opensql-switchover-vu-telemetry-20261004.md) | GCP, 70 req/s × 180초, `ha412sw400c`, 2026-10-04 | 12,601건 전송·미전송 0. 201 **12,516**건의 DB 누락·중복 **0** | HTTP 500 85건, 연속 201 최대 공백 6.204초 |
| [리더 VM 즉시 상실](docs/test-results/opensql-primary-unplanned-fault-load-20261004.md) | GCP, 30 req/s × 180초, `ha420vm00001`, 2026-10-04 | 새 primary 승격(timeline 12→13), 옛 노드 replica 재합류. 201 **4,349**건의 DB 누락·중복 **0** | HTTP 500 467·503 582·결과 불명 3건(1건 DB 반영). 같은 VM의 OpenProxy A도 함께 상실. 옛 VM은 자동 재시작 가드 실패로 수동 시작 |
| [etcd 두 멤버 중단](docs/test-results/opensql-etcd-http-failsafe-20261004.md) | GCP, 30 req/s × 150초 중 약 100초 정족수 상실, `e428htwo01`, 2026-10-04 | **4,500/4,500** HTTP 201·미전송 0, DB 4,500 ID 각 1행. TTL 이후 보수적 구간에서도 201 2,100건 | `failsafe_mode=true`에서 이 실행의 관측. 매 순간 split-brain 0은 증명하지 못했고 네트워크 분할은 미시험 |
| [DB 장애 중 인덱싱 Worker](docs/test-results/opensql-worker-primary-fault-20261004.md) | GCP, PDF 12 + DOCX 12 선접수 후 처리 중 postmaster 종료, `ha424fault003`, 2026-10-04 | **24/24** `INDEXED`, 청크 36·활성 임베딩 36, 청크·임베딩·Outbox 멱등 키 중복 **0** | 첫 Attempt는 lease 만료, 재시도로 성공. 약 5분 43초는 Worker 최종 복구 시간이며 DB RTO가 아님. Outbox 외부 전달은 미측정 |
| 권한 회수 복제 지연 ([재현](docs/test-results/opensql-permission-replica-lag-safety-20261001.md) → [수정 후](docs/test-results/opensql-admin-role-primary-consistency-20261001.md)) | 두 standby에 120초 apply delay 후 ADMIN 회수. 로컬 격리 JVM 2개 → SSH 터널 → GCP OpenSQL, `53688fbe7475` → `2075ca4865f1`, 2026-10-01 | 수정 전 OpenProxy 경유 `/admin/**` 요청 **200** → 관리자 인가를 primary 트랜잭션으로 고정한 뒤 같은 조건에서 **403** | HTTP 관리자 경로에 한정. 일반 API·WebSocket 권한의 최신성과 GCP 앱 A/B 경로 재시험은 범위 밖 |
| [하이브리드 검색 품질](docs/test-results/kangcheolung-%23456-hybrid-search.md) | 로컬 백엔드 + BGE-M3, 문서 5개·청크 83개, topK 10, 질문 세트 3개, 증빙 커밋 2026-10-10 | 벡터 단독 → 하이브리드: 111문항에서 1위 정답 **82 → 92**. 사전에 기준을 고정한 세트 3(39문항) MRR@10 **0.784 → 0.868** | 작은 코퍼스와 직접 작성한 질문. 세트 1·2는 설계에 영향을 줬고 고정 후 검증은 세트 3 한 번. 나빠진 질문 1개 |
| [DB·GCS 시점 복원](docs/test-results/db-gcs-point-in-time-recovery-20261015/) | OpenSQL 기준 백업 + 연속 WAL을 격리 경로에서 지정 시점 T로 복원, GCS 독립 사본, 2026-10-15 | T 이전 쓰기 A 1건·이후 B 0건. 복원 DB가 참조하는 파일 91건이 모두 사본에 있고, 복원 버킷 92객체 SHA-256 **92/92** 일치 | RPO·RTO 미측정, GCS 사본은 일회성 복사. 복원본에 백엔드를 연결한 [후속 시험](docs/test-results/db-gcs-service-recovery-20261015/)은 실제 모델 없이 일부 경로만 확인 |
| [PDF·DOCX 인덱싱 E2E](docs/test-results/gimin-%23145-final-performance-report.md) | 로컬 Apple Silicon 1대, PostgreSQL 17.8·MinIO·CPU BGE-M3, Worker Slot 2, 50·100문서 각 2회, 2026-08-11 | **31.046·32.063**문서/분. 합계 300문서·1,200 Vector에서 실패·재시도·미완료·중복 **0** | 로컬 개발 기준선이며 OpenSQL 원격 서버 성능이나 운영 SLO가 아님. OCR 제외 |

장애 중 HTTP 500·503과 결과 불명이 실제로 발생했고, 결과 불명 일부는 DB에 반영됐습니다. 따라서 위 결과는
무중단 가용성이나 모든 장애에서의 RPO 0을 입증하지 않습니다. postmaster 종료, 동기 standby 링크 차단 등
다른 장애 유형과 시험별 세부 조건은 [OpenSQL 핵심 부하·장애 검증](#opensql-핵심-부하장애-검증)과
[3노드 HA 통합 보고서](docs/test-results/opensql-three-node-ha-integrated-report-20261004.md)에 있습니다.

## 아키텍처

![DocGrid 최종 아키텍처: Vercel, Cloud Run, GCP 앱 A/B, OpenSQL 3노드와 관측 VM](docs/images/docgrid-final-architecture.png)

위 그림은 **2026-10-10 기준 최종 배포 구성**입니다. 사용자 요청이 백엔드에 도착하는 경로와
백엔드가 사용하는 저장소·AI·DB·관측 구성을 보여줍니다. 그림에 표시된 전체 경로가 하나의
E2E 시험으로 검증됐다는 뜻은 아닙니다.

### 사용자 요청 경로

- **REST API:** 브라우저의 `/api/backend/*` 요청은 Vercel 프런트엔드 서버 프록시 →
  Cloud Run Nginx 공개 게이트웨이 → GCP 내부 HTTP 로드 밸런서 → 백엔드 A/B 순서로 전달됩니다.
- **STOMP WebSocket:** 브라우저는 별도 WSS 경로로 Cloud Run 공개 게이트웨이에 연결합니다.
  이후 내부 로드 밸런서를 거쳐 백엔드 A/B에 도착합니다.

내부 로드 밸런서는 readiness 검사를 통과한 백엔드로 요청을 보냅니다. 로컬 개발·시험에서는
이 배포 경로 대신 [단일 백엔드와 Compose 서비스](#빠른-실행)를 사용합니다.

### 데이터 저장과 AI 처리

백엔드 A/B는 문서 원본을 **GCS 버킷**에 저장하고, 문서·인덱싱 작업·검색 데이터는 **OpenSQL**에
기록합니다. GCS는 VM 디스크가 아닌 Google 관리형 저장소입니다.

문서와 질문의 임베딩은 **FastAPI/BGE-M3**가 생성합니다. 검색된 문서를 바탕으로 답변을 만들 때는
**Ollama**를 사용합니다. 공용 **Redis**는 역할 캐시와 백엔드 간 대시보드 갱신 신호에 사용합니다.

### OpenSQL 연결과 역할 전환

OpenSQL 3노드는 한 시점에 **primary 1개와 standby 2개**로 운영되며, 장애나 역할 전환에 따라
primary가 바뀔 수 있습니다.

- 일반 앱의 JDBC 연결은 DB node2·node3에 각각 배치된 **OpenProxy A/B**를 사용합니다.
- **Flyway**는 별도 계정과 경로로 DB 3개 주소를 확인해 현재 primary에 연결합니다.
- **Patroni**는 노드의 역할과 복제를 관리하고, **etcd 3개 멤버**는 정족수 2/3으로 상태를
  조정합니다.

`loadBalanceHosts=true`는 새 JDBC 연결이 사용할 **프록시 후보**를 분산하는 설정입니다.
HTTP 요청이나 개별 SQL을 균등하게 나누는 설정은 아닙니다.

### 대시보드 갱신

`DASHBOARD_CROSS_NODE_ENABLED=true`일 때 백엔드 A/B는 공용 Redis Pub/Sub으로 갱신 신호를
주고받습니다. 신호를 받은 백엔드는 **자신의 DB에서 상태를 다시 조회**한 뒤, 자신의 STOMP
구독자에게 결과를 보냅니다.

Pub/Sub 신호는 재조회 계기이며, 내구성 있는 메시지 큐로 사용하지 않습니다. 단일 로컬 백엔드는
자체 STOMP SimpleBroker로 대시보드 메시지를 전송합니다.

### 문서와 검색의 내부 흐름

1. **문서 접수:** `DocumentUploadFacade`가 파일 형식과 해시를 확인하고 선택된 저장소에 원본을
   기록합니다. `opensql-ha` 프로필에서는 OpenProxy를 경유하는 DB 트랜잭션으로 문서·버전·
   인덱싱 Job을 만듭니다. DB 저장에 실패하면 미사용 원본 객체의 삭제를 시도합니다.
2. **인덱싱:** `INDEXING_WORKER_ENABLED=true`인 백엔드가 Job을 선점합니다. 원본 읽기 →
   Parsing·Chunking → FastAPI/BGE-M3 Embedding → pgvector 저장 순서로 처리하며,
   Lease·재시도·복구 상태를 DB에 남깁니다.
3. **검색과 답변:** `SearchFacade`가 질문을 임베딩하고 문서 권한을 확인한 뒤 pgvector 후보를
   조회합니다. 후보의 권한을 다시 확인하고, RAG Worker가 검색 문맥을 Ollama에 보내 답변과
   인용을 저장합니다.

### 관측 구성과 검증 범위

별도 관측 VM의 **Prometheus·Grafana·Blackbox Exporter**가 앱·DB·프록시·임베딩 경로를
관측합니다. **k6 부하 VM**은 시험 지표를 Remote Write로 전송하는 구성입니다.

저장소에는 다음 경로의 실행 기록이 있습니다.

- [GCP 앱 A/B·OpenSQL·GCS의 PDF 저장·조회](docs/test-results/gimin-387-gcp-app-gcs-opensql-baseline-20261002.md)
- [백엔드 A/B 간 대시보드 갱신](docs/test-results/gimin-382-dashboard-cross-backend-refresh-20261002.md)
- [OpenSQL 3노드 장애 시험](docs/test-results/opensql-three-node-ha-integrated-report-20261004.md)

## 기술 스택

| 영역 | 기술 |
|---|---|
| Frontend | React 19, TypeScript 5, Tailwind CSS 4, Vinext, Vite |
| Backend | Java 17, Spring Boot 3.5.16, Spring Security, Spring Data JPA, Spring AI MCP, STOMP WebSocket, Flyway, Spring Boot Actuator |
| Database · HA | OpenSQL 17.8 / PostgreSQL 17, pgvector 0.8.1, OpenProxy A/B, Patroni, etcd 3노드 |
| AI | FastAPI, BAAI/bge-m3 Embedding, Ollama, qwen2.5:7b |
| Storage | GCS (GCP 원본), MinIO (로컬), Local Filesystem, 선택형 AWS S3 Adapter |
| Cache · 메시지 | 공용 Redis VM (GCP 역할 캐시·대시보드 Pub/Sub), Valkey 9.1 (로컬 Compose), Spring Data Redis |
| 배포 · 네트워크 | Vercel, Cloud Run Nginx 공개 게이트웨이, Google Cloud Load Balancing, Compute Engine VM, IAP (위 구성도 기준) |
| 관측 · 부하 | RAGOps Dashboard, Micrometer/Prometheus, Grafana, Blackbox Exporter, k6 Remote Write (위 구성도 기준); Alertmanager (로컬 monitoring profile) |
| Test | JUnit 5, Gradle, Node.js Test Runner, k6 |

## 빠른 실행

아래는 GCP 접근 권한 없이 로컬 PostgreSQL·MinIO·Valkey로 핵심 기능을 확인하는 경로입니다.
명령은 저장소 루트에서 실행합니다.

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
npm --prefix frontend ci
```

`.env.example`은 로컬 PostgreSQL·MinIO 접속값을 제공하지만 인덱싱 Worker는 기본값으로 **꺼져
있습니다**. 업로드 후 자동 인덱싱까지 확인하려면 로컬 `.env`에
`INDEXING_WORKER_ENABLED=true`를 추가합니다. 실제 운영 비밀번호와 외부 서버 인증정보는 README,
Issue 또는 Commit에 기록하지 않습니다.

### 3. PostgreSQL, MinIO, Valkey, BGE-M3 실행

```bash
docker compose up -d --build postgres minio valkey embedding-server
docker compose logs -f embedding-server
```

BGE-M3는 첫 실행 시 모델 다운로드와 로딩이 필요하므로 준비 완료 로그를 확인합니다. 소요 시간은
호스트와 네트워크에 따라 다릅니다. `Ctrl+C`로 로그 조회만 종료한 뒤 Health Check를 실행합니다.

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

초기 설치 후 재기동할 때는 Valkey, BGE-M3, Ollama를 전용 스크립트로 기동하고 연결 상태를 확인할 수
있습니다. 위의 `docker compose up`와 같은 서비스를 다시 시작하는 명령이므로 둘 중 한 경로를 선택합니다.

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
프런트엔드가 이미 `localhost:3000`을 사용하므로 Grafana의 호스트 포트는 3001로 지정합니다.

```bash
GRAFANA_PORT=3001 docker compose --profile monitoring up -d prometheus alertmanager grafana
```

Prometheus는 감시 대상이나 Alertmanager의 health 상태를 시작 조건으로 사용하지 않는다. 따라서
Embedding Provider가 준비되지 않아도 먼저 기동해 수집 실패와 경보를 기록한다.

- Prometheus Target: `http://localhost:9090/targets`
- Grafana Dashboard: `http://localhost:3001/d/docgrid-operations/docgrid-operations`
- 설정과 기존 Prometheus 연결: [monitoring/prometheus/README.md](monitoring/prometheus/README.md)
- Grafana 화면과 접근 범위: [monitoring/grafana/README.md](monitoring/grafana/README.md)

### 6. 핵심 흐름 확인

1. 회원가입 또는 로그인
2. PDF/DOCX 문서 업로드
3. `INDEXING_WORKER_ENABLED=true`일 때 문서 인덱싱 상태가 완료로 바뀌는지 확인
4. 문서 내용으로 AI 검색
5. RAG 답변과 인용 근거 확인

### 종료

```bash
docker compose stop postgres minio valkey embedding-server
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
| `s3` | 코드에 남아 있는 선택형 Adapter; 현재 GCP 저장소에는 미사용 | `AWS_REGION`, AWS Credential, `STORAGE_BUCKET` |
| `gcs` | Google Cloud Storage 환경 | ADC 인증, `STORAGE_BUCKET` |

GCS를 선택할 때는 실행 주체에 대상 Bucket의 Object 생성·조회·삭제 권한을 부여하고
`STORAGE_TYPE=gcs`, `STORAGE_BUCKET`을 설정합니다. 로컬에서는 Application Default Credentials를,
Google Cloud 실행 환경에서는 연결된 Service Account를 사용합니다. 서비스 계정 키는 `.env`에 넣지 않습니다.
기존 MinIO/S3 파일의 DB 위치는 GCS로 자동 이전되지 않으므로, 기존 파일이 있는 환경은 파일과 DB 위치를
이전하기 전까지 저장소 설정만 바꾸지 않아야 합니다.

### 로컬 백엔드에서 OpenSQL 3노드 연결

로컬 백엔드에서 GCP의 OpenSQL 3노드를 사용할 때는 `SPRING_PROFILES_ACTIVE=opensql-ha`
프로필을 사용합니다. 실제 주소·자격증명은 이 저장소에 두지 않고 실행 환경에 주입합니다.

| 설정 | 연결 대상·역할 |
|---|---|
| `OPENSQL_APP_JDBC_URL` | OpenProxy A/B의 다중 호스트 JDBC URL. 신규 연결의 프록시 후보를 선택합니다. |
| `OPENSQL_APP_USER`, `OPENSQL_APP_PASSWORD` | 최소 권한 애플리케이션 계정. |
| `OPENSQL_MIGRATION_JDBC_URL` | DB node1/2/3의 다중 호스트 JDBC URL과 `targetServerType=primary`. Flyway 전용 경로입니다. |
| `OPENSQL_MIGRATION_USER`, `OPENSQL_MIGRATION_PASSWORD` | 앱 계정과 분리된 DDL 마이그레이션 계정. |
| `STORAGE_TYPE=gcs`, `STORAGE_BUCKET` | GCS 버킷과 로컬 실행 주체의 Application Default Credentials. GCP VM에서는 연결된 서비스 계정을 사용합니다. |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | 로컬 백엔드가 사용할 Valkey의 Redis 프로토콜 주소. 변수명과 Spring Data Redis 클라이언트는 유지됩니다. |
| `DASHBOARD_CROSS_NODE_ENABLED` | 단일 백엔드 기능 테스트에서는 기본값 `false`. 다중 백엔드 시험에서만 공용 캐시 Pub/Sub를 사용하도록 `true`로 설정합니다. |
| `EMBEDDING_SERVER_URL`, `OLLAMA_SERVER_URL` | 로컬 백엔드가 접근할 수 있는 FastAPI/BGE-M3와 Ollama 주소. 로컬 기본값은 각각 `http://localhost:8000`, `http://localhost:11434`입니다. |

앱·프록시·DB 연결 계약은
[OpenProxy/Hikari 실접속 기록](docs/test-results/gimin-opensql-openproxy-hikari-troubleshooting-20260924.md)과
[`application-opensql-ha.yml`](backend/src/main/resources/application-opensql-ha.yml)에 있습니다.
로컬에서 GCP DB에 접속할 때는 승인된 SSH 터널과 전용 자격증명이 필요하며, DB 포트를 인터넷에
공개하는 실행법은 제공하지 않습니다. 공급사 설치 파일, 라이선스, DB 자격증명과 SSH 키도 저장소에
포함하지 않습니다.
macOS 팀원별 터널 자동 시작 절차는 [로컬 OpenSQL SSH 터널 가이드](docs/runbooks/opensql-local-tunnels.md)를 따릅니다.

## 테스트

일반 회귀 테스트는 외부 장시간 Benchmark와 실제 인프라 E2E를 제외하지만 PostgreSQL과 Valkey를
사용하는 통합 테스트를 포함합니다. 두 Service를 먼저 준비합니다.

```bash
docker compose up -d --wait postgres valkey
./backend/gradlew -p backend test
npm --prefix frontend test
```

파일 저장소 Adapter와 실제 자동 Worker 전체 흐름은 별도 E2E로 검증합니다.

```bash
docker compose up -d --build --wait postgres minio valkey embedding-server

DB_HOST=127.0.0.1 DB_PORT=55432 \
  ./backend/gradlew -p backend storageWorkerE2eTest
```

## OpenSQL 핵심 부하·장애 검증

**OpenSQL 3노드의 실제 앱 쓰기 부하와 장애 대응**을 중심으로
정리했습니다. 아래 결과는 실행 시점·환경이 서로 달라 하나의 서비스 가용성 수치로 합칠 수 없습니다.

| 시험 | 실행 조건 | 관측 결과와 한계 | 실행 기록 |
|---|---|---|---|
| **정상 쓰기 부하** | GCP 내부 LB → 백엔드 A/B → OpenProxy A/B → OpenSQL 3노드, 150 req/s × 60초 × 3회 | 회차마다 HTTP 201 **9,001건**, DB 누락·중복·실패·드롭 **0건**. 200·250 req/s는 안정 기준선에서 제외 | [GCP HTTP 쓰기 기준선](docs/test-results/gimin-389-opensql-ha-http-write-baseline-20261002.md) |
| **OpenProxy 한쪽 장애** | GCP에서 A와 B를 각각 중단하고, 살아 있는 프록시로 새 JDBC 연결이 이동하는지 측정 | 새 연결 전환은 확인했지만 재실행에서 HTTP 500 **A 3건·B 8건**. 201 응답의 DB 누락·중복은 0건이어도 **무중단 시험은 실패** | [장애 부하](docs/test-results/gimin-391-openproxy-ab-fault-load-20261003.md), [500 원장 재분석](docs/test-results/gimin-393-openproxy-http-500-analysis-20261003.md) |
| **Patroni 계획 역할 전환** | GCP OpenSQL 3노드에서 양방향 계획 switchover 후 이전 리더의 replica 자동 재합류 확인 | streaming 복귀 **19초·18초**, 수동 컨테이너 재시작 0회. HTTP 부하를 건 리더 강제 상실이나 서비스 RTO/RPO 측정은 아님 | [3노드 재합류 검증](docs/test-results/gimin-401-patroni-auto-rejoin-20261003.md) |
| **primary 프로세스 장애** | GCP 3노드에서 PostgreSQL `postmaster` 종료, 30 req/s × 120초 | HTTP 201 **3,372건·500 227건·결과 불명 2건**. DB가 같은 노드에서 재시작했고 관측한 201 ID 누락·중복은 0건; 불명 1건은 DB에 반영 | [비계획 장애 실측](docs/test-results/opensql-primary-unplanned-fault-load-20261004.md) |
| **리더 VM 즉시 상실** | GCP 리더 VM과 같은 VM의 OpenProxy A가 함께 상실, 30 req/s × 180초 | HTTP 201 **4,349건·500 467건·503 582건·결과 불명 3건**. 새 primary 승격 후 옛 노드 재합류; 201 ID 누락·중복 0건은 해당 실행의 관측값 | [비계획 장애 실측](docs/test-results/opensql-primary-unplanned-fault-load-20261004.md) |
| **DB 장애 중 인덱싱 복구** | GCP에서 PDF 12건·DOCX 12건 선접수 후 primary 프로세스 종료, 시험 전용 Worker 유지 | 최종 **24/24건 `INDEXED`**, Chunk 36개·ACTIVE Vector 36개·자연키 중복 0. Lease 만료 후 두 번째 Attempt 성공; 약 5분 43초는 DB RTO가 아님 | [Worker 장애 복구](docs/test-results/opensql-worker-primary-fault-20261004.md) |
| **복제 지연·권한 안전성** | GCP 3노드 standby 적용을 지연시킨 뒤 ADMIN 회수, OpenProxy 경유 관리자 요청 재시험 | 수정 전 뒤처진 standby 때문에 HTTP 200을 재현했고, 수정 후 primary 확인 경로는 **403**. 일반 API 전체의 최신성 보장은 아님 | [문제 재현](docs/test-results/opensql-permission-replica-lag-20260929.md), [수정 후 검증](docs/test-results/opensql-admin-role-primary-consistency-20261001.md) |

etcd 두 멤버 중단과 동기 standby 링크 차단도 각각 앱 HTTP 경로에서 시험했습니다. 두 결과와
성공 응답 사이 공백·결과 불명의 해석은 [3노드 HA 통합 보고서](docs/test-results/opensql-three-node-ha-integrated-report-20261004.md)에
따로 기록했습니다. **관측된 201 응답의 DB 행 보존**은 해당 표본의 결과이며 무중단 운영이나
모든 장애에서의 RPO 0 보장이 아닙니다. 비계획 리더 VM 상실의 안정화 관측 구간 약 52.9초도
연속 쓰기 중단 시간이나 일반적인 RTO로 해석하지 않습니다. 다른 기능·호환성 시험은
[전체 실행 기록](docs/test-results/)에 보존되어 있습니다.

## 문서

| 문서 | 내용 |
|---|---|
| [Backend README](backend/README.md) | PostgreSQL, BGE-M3, Ollama 실행 |
| [Frontend README](frontend/README.md) | Web 실행, 인증과 API 연결 범위 |
| [로컬 DB 실행](docs/local-db.md) | PostgreSQL 17 + pgvector 로컬 기준선 |
| [Grafana 운영 Dashboard](monitoring/grafana/README.md) | Prometheus 지표 화면·로컬 접근 설정 |
| [설계 문서](docs/design/) | 기능·인프라의 설계 의사결정 |
| [실행 결과](docs/test-results/) | 위 테스트 표의 상세 절차·실행별 증거·제약 |

테스트 표의 각 링크에서 실행 시점, 사용한 환경, 실패·재시도와 원본 증거를 확인할 수 있습니다.

## 저장소 구조

```text
.
├── backend/          # Spring Boot API, Worker와 BGE-M3 서버
├── frontend/         # DocGrid Web Application
├── docs/design/      # 설계 의사결정
├── docs/test-results/# 실행된 Test Plan, 측정과 결과
├── docker/           # 로컬 인프라 초기화
├── monitoring/       # Prometheus·Alertmanager·Grafana 설정과 Dashboard
├── scripts/          # 검증·보고 자동화
└── docker-compose.yml
```

## 라이선스 및 외부 구성요소

DocGrid의 자체 소스코드와 문서는 [Apache License 2.0](LICENSE)에 따라 배포합니다.

| 외부 구성요소 | 사용 방식 | 라이선스 경계 |
|---|---|---|
| [BAAI/bge-m3](https://huggingface.co/BAAI/bge-m3) | Embedding Server 첫 실행 시 다운로드 | MIT License |
| [qwen2.5:7b](https://ollama.com/library/qwen2.5:7b) | 사용자가 `ollama pull`로 다운로드 | Apache License 2.0 |
| [Ollama](https://github.com/ollama/ollama) | 사용자가 Native로 설치해 `qwen2.5:7b`를 실행 | MIT License |
| OpenSQL | 외부 개발·검증 DB로 연결 | 공급사 배포본과 License는 저장소에 포함하지 않음 |
| Python 의존성 (임베딩 서버) | `backend/embedding-server/requirements.txt`로 설치 | fastapi MIT, uvicorn BSD, FlagEmbedding MIT, torch BSD, transformers·peft Apache-2.0, numpy BSD, prometheus-client Apache-2.0 AND BSD-2-Clause. Linux x86_64에서는 CPU 전용 torch를 설치해 NVIDIA `nvidia-*` 패키지를 설치하지 않음 |
| Java 의존성 | Gradle로 획득 (`backend/build.gradle`) | Apache-2.0·MIT·BSD 위주. Hibernate ORM 6.6은 LGPL-2.1 이상, Logback은 EPL-2.0과 LGPL-2.1 듀얼, Jakarta·AspectJ는 EPL-2.0, 테스트용 H2는 MPL-2.0·EPL-1.0, JUnit은 EPL-2.0 |
| npm 의존성 | npm으로 획득 (`frontend/package-lock.json`) | 실행 의존성 4개는 react·react-dom·scheduler MIT, drizzle-orm Apache-2.0. 나머지는 빌드·개발 도구이며 MIT·Apache-2.0·ISC·BSD 위주이고 MPL-2.0, LGPL-3.0(sharp libvips) 등을 포함 |
| Valkey 9.1 (Container Image) | 로컬 Compose 캐시 서버. Spring Data Redis(Apache-2.0)·Lettuce(MIT) 클라이언트로 접속 | BSD-3-Clause |
| pgvector/pgvector 0.8.1 (Container Image) | 로컬 Compose PostgreSQL 17 | pgvector는 PostgreSQL License. 이미지는 PostgreSQL과 OS 패키지를 포함 |
| Prometheus v3.5.5, Alertmanager v0.33.1 (Container Image) | 선택형 `monitoring` profile | Apache-2.0 |
| Grafana 13.2.3 (Container Image) | 선택형 `monitoring` profile. 수정하지 않고 별도 컨테이너로 실행 | AGPL-3.0 (OSI 승인) |
| MinIO (Container Image) | 로컬 Compose의 `minio` 서비스. 팀은 현재 사용하지 않음(파일 저장소는 GCS). 공식 `minio/minio` 이미지는 2026-10-09 기준 새로 받을 수 없음(`docker pull` 거부 확인) | AGPL-3.0 |

외부 모델, 라이브러리, Container Image와 OpenSQL 배포본은 DocGrid의 Apache License 2.0 적용 범위에
포함되지 않습니다. 위 표의 라이선스는 2026-10-09 기준 각 패키지의 메타데이터(POM·PyPI·npm
lockfile)와 업스트림 라이선스 원문을 확인한 결과이며, 제출·배포 시점에 다시 확인합니다.
