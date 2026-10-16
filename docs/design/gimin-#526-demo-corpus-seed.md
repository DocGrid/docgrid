# 예제 문서 seed 스크립트와 README 빠른 실행 Worker 설명 정정 (#526)

closes #526

## 1. 배경

- README 「빠른 실행」을 끝내도 넣어 볼 예제 문서가 없다. 심사위원이 검색·RAG를 확인하려면 문서를 직접 골라
  하나씩 업로드해야 한다. 경쟁작은 명령 한 줄로 예제 문서를 넣고 바로 검색할 수 있다.
- README 2단계와 6단계 3번은 "인덱싱 Worker는 기본값으로 꺼져 있다"고 안내했다. 실제로는 `.env.example`의
  `SPRING_PROFILES_ACTIVE=local`과 `application-local.yml`의 `indexing.worker.enabled: ${INDEXING_WORKER_ENABLED:true}`
  때문에 빠른 실행에서 Worker가 켜진다(2026-08-15 `3fe8385`부터). `application.yml` 공통 기본값은 `false`다.
- Ollama가 없을 때의 동작이 README에 없었다. 코드상 RAG Worker(`RagExecutionConfig`)는 활성화 조건 없이 항상
  등록되고, Ollama 호출이 실패하면 `RagFacade`가 최상위 검색 후보 원문을 인용한 대체 답변을 `FAILED`로 저장한다.

## 2. 결정

| 항목 | 내용 |
|---|---|
| 예제 문서 | `examples/demo-corpus/`에 가상 회사 사내 규정 15건(PDF 5·DOCX 5·HWPX 5)과 제목·설명을 담은 `manifest.json` |
| 스크립트 | `scripts/demo/seed_demo_corpus.py`. Python 3.9 이상 표준 라이브러리만 사용 |
| 공개 범위 | `PUBLIC`. seed 계정 외에 심사위원이 웹에서 새로 가입한 계정도 바로 검색할 수 있어야 한다 |
| 중복 방지 | `Idempotency-Key: docgrid-demo-{파일 SHA-256}` |
| README | 2·4·6단계 문구 정정, 6단계에 seed 명령·실제 출력·추천 질문 추가 |
| 범위 | 백엔드 코드·설정과 프런트엔드는 바꾸지 않는다 |

### 2.1 예제 문서

- 본문은 직접 작성했다. 외부 문서를 복사하지 않았고 모든 문서에 "가상 회사 시연용 예제"라고 적었다.
- Backend가 파싱에 쓰는 라이브러리(PDFBox 3.0.8, POI 5.5.1, hwpxlib 1.0.9)로 각 형식을 만들어 파서 호환성을
  보장했다. DOCX는 `Title`·`Heading1` 스타일을 써서 `DocxDocumentParser`가 Section 경로를 만든다.
- PDF에는 나눔고딕(SIL OFL 1.1, `fsType` 0) 글자 부분집합만 들어간다. 글꼴 파일은 저장소에 넣지 않았다.
- 15건 합계 약 300KB, Chunk 42개라 CPU 환경에서도 인덱싱이 수십 초 안에 끝난다(3장).
- 생성 방법과 라이선스는 `examples/demo-corpus/README.md`, 외부 구성요소 표는 루트 README에 적었다.

### 2.2 스크립트 흐름

1. `manifest.json`을 읽고 파일 존재와 확장자를 확인한다.
2. 임베딩 서버 `GET {embedding-url}/health/ready`를 확인한다. 준비 전이면 업로드하지 않고 종료한다(2.4).
3. `POST /auth/login`. `USER-004`(계정 없음 또는 비밀번호 불일치)이면 `POST /auth/signup` 뒤 다시 로그인한다.
4. 문서마다 `POST /api/documents`(multipart `file`·`title`·`description`·`visibility`)를 호출한다. 응답 직후
   `GET /api/documents/{id}/status`가 이미 INDEXED면 이전 실행에서 올린 문서로 표시한다.
5. 3초마다 상태 API를 조회해 INDEXED·FAILED가 되거나 `--timeout`(기본 600초)에 닿을 때까지 기다린다.
   90초 동안 상태 집계가 바뀌지 않으면 원인 후보를 한 번 출력하고 계속 기다린다.
6. 요약(업로드·INDEXED·실패·미완료 건수, 업로드·전체 소요 시간)과 원인 후보를 출력한다.
7. INDEXED 문서가 있으면 `POST /search`(topK 3)로 예시 검색 1건을 실행하고, `GET /search/{queryId}`로
   RAG 답변을 최대 `--rag-wait`(기본 60초) 기다려 출력한다.

비밀번호와 Access Token은 메모리에만 두고 출력하지 않는다.

### 2.3 멱등 키

서버(`InitialUploadRequestService`)는 사용자별 `(user_id, request_key)`로 최초 결과를 저장하고, 같은 키의
입력 지문(파일 해시·파일명·Content-Type·크기·제목·설명·공개 범위)이 다르면 `DOCUMENT-UPLOAD-003`으로 거부한다.
키는 파일 바이트의 SHA-256으로 만든다(77자, 서버 허용 패턴 `[A-Za-z0-9._~-]{1,128}` 안).

| 상황 | 결과 |
|---|---|
| 같은 계정으로 재실행 | 같은 문서 ID를 돌려받고 새 문서를 만들지 않음 |
| 다른 계정으로 실행 | 키가 사용자별이라 그 계정 소유의 새 문서 15건 생성 |
| `manifest.json`의 제목·설명만 바꾼 뒤 같은 계정으로 재실행 | `DOCUMENT-UPLOAD-003`. 스크립트가 "다른 제목·설명으로 이미 업로드됨"으로 안내 |
| 같은 키로 처리 중인 요청이 있음(`DOCUMENT-UPLOAD-004`) | 5초 간격 최대 6회 재시도 |

### 2.4 상태 분류와 원인 안내

`GET /api/documents/{id}/status` 응답을 네 상태로 줄인다.

| 분류 | 조건 |
|---|---|
| INDEXED | `processingVersion`이 없고 `currentVersion.status`가 `INDEXED` |
| PENDING | Version `UPLOADED`이고 Job `PENDING` (Worker가 아직 가져가지 않음) |
| PROCESSING | 그 밖의 처리 중 상태. 재시도 대기(Job `PENDING`, Version이 처리 단계)도 포함 |
| FAILED | 문서 `FAILED`, Job `FAILED`·`CANCELED` 또는 Version `FAILED` |

원인 후보는 다음 순서로 낸다.

1. 임베딩 서버가 응답하지 않음: 미완료나 실패 문서가 있고 `/health/ready`가 실패할 때
2. Worker가 Job을 가져가지 않음: 미완료 문서가 한 번도 PENDING을 벗어나지 않았을 때.
   `INDEXING_WORKER_ENABLED=false`, local이 아닌 프로필, Backend 시작 로그의 Worker 등록 여부를 안내한다
3. 처리가 느림: Worker와 임베딩 서버가 모두 동작하는데 시간 안에 끝나지 않았을 때 `--timeout` 증가를 안내한다

Worker는 Job마다 최대 3회(10초부터 지수 Backoff)만 자동 재시도하고, 실패 Job 재처리 API(`/admin/embedding-jobs`)는
ADMIN 전용이다. 그래서 임베딩 서버가 준비되기 전에 올리면 일반 데모 계정으로는 복구하기 어렵다. 이 때문에
업로드 전에 임베딩 서버 준비를 확인하고, 준비 전이면 업로드하지 않고 종료한다. Backend가 다른 임베딩 주소를 쓰면
`--embedding-url` 또는 `--skip-embedding-check`를 쓴다.

## 3. 명세

### 3.1 실행

```bash
python3 scripts/demo/seed_demo_corpus.py [옵션]
```

| 옵션 | 환경변수 | 기본값 | 설명 |
|---|---|---|---|
| `--base-url` | `DOCGRID_BASE_URL` | `http://localhost:8080` | Backend 주소 |
| `--email` | `DOCGRID_DEMO_EMAIL` | `demo@docgrid.local` | 업로드 계정 |
| (없음) | `DOCGRID_DEMO_PASSWORD` | 없음 | 없으면 터미널에서 입력받고, 터미널이 아니면 종료 |
| `--name`, `--department-id` | | `DocGrid 데모`, `1` | 회원가입할 때만 사용 |
| `--corpus-dir` | | `examples/demo-corpus` | 예제 문서 위치 |
| `--timeout` | `DOCGRID_SEED_TIMEOUT` | `600` | INDEXED 대기 상한(초) |
| `--poll-interval` | | `3` | 상태 조회 주기(초) |
| `--embedding-url` | `EMBEDDING_SERVER_URL` | `http://localhost:8000` | 사전 확인과 진단용 임베딩 서버 주소 |
| `--skip-embedding-check` | | 꺼짐 | 업로드 전 임베딩 서버 확인 생략 |
| `--query` | | 재택근무 질문 | 예시 검색 질문 |
| `--rag-wait` | | `60` | RAG 답변 대기 상한(초) |

### 3.2 종료 코드

| 코드 | 의미 |
|---|---|
| 0 | 모든 예제 문서가 INDEXED |
| 1 | 일부 업로드 실패, 인덱싱 실패 또는 시간 안에 끝나지 않음 |
| 2 | 스크립트 중단: 예제 문서 누락, 임베딩 서버 미준비, 비밀번호 없음, 로그인·회원가입 실패, Backend 연결 실패나 상태 조회 오류 |
| 130 | 사용자가 중단(Ctrl+C). 다시 실행해도 중복이 생기지 않음 |

### 3.3 사용하는 API와 에러 처리

| API | 정상 | 처리하는 에러 |
|---|---|---|
| `POST /auth/login` | 200, `data.accessToken` | 401 `USER-004` → 회원가입 시도 |
| `POST /auth/signup` | 201 | 409 `USER-002` → "계정은 있지만 비밀번호가 다름", 400 → 비밀번호 길이 안내 |
| `POST /api/documents` | 201 | 409 `DOCUMENT-UPLOAD-004` → 재시도, 409 `DOCUMENT-UPLOAD-003` → 메타데이터 충돌 안내, 그 밖은 해당 문서 실패로 기록 |
| `GET /api/documents/{id}/status` | 200 | 404 → 이 계정에서 삭제된 문서로 기록, 그 밖은 중단 |
| `POST /search` | 200 | 실패 시 메시지만 출력 |
| `GET /search/{queryId}` | 200 | 실패 시 메시지만 출력 |

## 4. 검증

2026-10-16, MacBook Pro(Apple M5, 24GB). 기존 개발 DB와 섞이지 않도록 새 PostgreSQL 17 + pgvector 0.8.1
컨테이너(55460)와 Valkey(6390)를 띄우고, 이 브랜치 Backend를 `SERVER_PORT=18080`으로 실행했다.
`SPRING_PROFILES_ACTIVE`·`INDEXING_WORKER_ENABLED`는 설정하지 않았다. BGE-M3는 기존 Docker 컨테이너(준비 완료),
Ollama는 미실행이다.

| 시나리오 | 결과 |
|---|---|
| Worker 기본값 | 별도 설정 없이 `local` 프로필이 활성화되고 시작 로그에 "인덱싱 Worker를 등록했습니다" 출력 |
| 새 DB 첫 실행 | 업로드 15/15(1.2초), INDEXED 15/15(22.4초), 스크립트 전체 23.6초(`time` 26.28초), 종료 코드 0 |
| 같은 계정 재실행 | 15건 모두 "이미 INDEXED", 전체 0.5초. DB의 데모 계정 문서 15건·멱등 요청 15건·Chunk 42개로 중복 없음 |
| Ollama 미실행 | 검색 결과 3건 정상. RAG는 2.0초 만에 `FAILED`와 원문 인용 대체 답변 |
| 다른 부서의 새 계정 | 추천 질문 3개(PDF·DOCX·HWPX) 모두 해당 문서가 1위 |
| `INDEXING_WORKER_ENABLED=false` | 15건 모두 PENDING, 90초 뒤와 종료 시 "Worker가 Job을 가져가지 않음" 안내, 시간 초과 |
| 임베딩 서버 주소 오류 | 사전 확인에서 업로드 없이 종료 코드 2. `--skip-embedding-check`로 진행하면 약 1분 50초 뒤 15건 FAILED, "임베딩 서버가 응답하지 않음" 안내 |
| 단위 테스트 | `python3 -m unittest discover -s scripts/demo` 12개 통과(Python 3.9.6) |
| 기존 백엔드 테스트 | 위 격리 컨테이너를 대상으로 `./backend/gradlew -p backend test` 251개 클래스·1,501개 통과, 실패 0, skip 5 |

## 5. 범위 밖

- 통합 실행 CLI(`./docgrid up`)와 이미지 패키징은 대회 기간 위험이 커서 10/28 이후로 미룬다.
- Ollama와 `qwen2.5:7b`가 있을 때의 RAG 성공 경로는 이번에 다시 실행하지 않았다. 모델(4.7GB)이 이 PC에 없어
  다운로드가 필요했다. 같은 경로는 #502 새 PC 재현에서 `SUCCESS` 13.45초로 확인했다.
- 실패 Job 재처리 권한(ADMIN 전용)과 Job 재시도 횟수는 바꾸지 않는다.
- `scripts/` Python 테스트는 CI에서 실행하지 않는다(기존 `scripts/opensql` 테스트와 같음).
