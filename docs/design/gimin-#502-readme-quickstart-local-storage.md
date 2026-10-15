# README 빠른 실행을 새 PC에서 끝까지 재현되게 수정 (#502)

closes #502

#454에서 "기본 경로 분리(compose profile, `.env.example`, README 빠른 실행 변경)는 팀 협의 후 별도 이슈"로
남긴 항목이다. 팀 협의를 거쳐 진행한다. 새로 클론한 PC에서 README를 그대로 따라 하는 재현 검증 중
`JWT_SECRET` 누락을 추가로 발견해 같은 이슈에서 고친다.

## 1. 배경

2차 평가(10/12~10/28)는 평가 시점의 저장소를 새 PC에 클론해 README를 따라 실행한다. 수정 전 README
「빠른 실행」은 새 PC에서 두 곳에서 멈춘다.

### 1.1 3단계: MinIO 이미지를 받을 수 없다

- `docker-compose.yml`의 `minio` 서비스는 태그 없는 `image: minio/minio`를 사용한다.
- 공식 이미지는 새로 받을 수 없다. 2026-10-09 `docker pull` 거부(#454)에 이어 2026-10-15에도 Docker Hub
  저장소 API(`hub.docker.com/v2/repositories/minio/minio/`)가 404를 반환했다.
- README 3단계 `docker compose up -d --build postgres minio valkey embedding-server`가 minio를 포함하고,
  `.env.example`은 `STORAGE_TYPE=minio`를 지정한다.
- 팀 개발 PC에는 이미지가 남아 있어 드러나지 않았다.

반면 애플리케이션은 이미 MinIO 없이 실행할 수 있다.

| 확인 위치 | 내용 |
|---|---|
| `application.yml` | `storage.type: ${STORAGE_TYPE:local}` |
| `FileStorageProperties` | 기본 `FileStorageType.LOCAL`, Local이면 Bucket 미설정 시 `docgrid` 자동 사용 |
| `LocalFileStorageService` | `@ConditionalOnProperty(havingValue = "local", matchIfMissing = true)` |
| `MinioStorageService`, `MinioConfig` | `@ConditionalOnProperty(havingValue = "minio")`. 다른 저장소에서는 MinIO Endpoint·Credential을 요구하지 않음 |

### 1.2 5단계: `JWT_SECRET`이 없어 Backend가 기동하지 않는다

- `application.yml`의 `jwt.secret: ${JWT_SECRET}`은 기본값이 없고 `JwtProvider`가 생성자에서 주입받는다.
- `.env.example`, README, `backend/README.md` 어디에도 `JWT_SECRET`이 없다. 팀원은 각자 `.env`에 값을 두고
  있어 드러나지 않았다.
- 새 PC에서는 `bootRun`이 `Could not resolve placeholder 'JWT_SECRET'`로 Context 초기화에 실패한다.
- local 프로필에서 기본값 없이 필수인 Placeholder는 이 값과 `MINIO_*`(`.env.example`에 있음)뿐이다.

## 2. 결정

**애플리케이션 코드와 Spring 설정은 바꾸지 않고, 실행 설정과 README만 바꾼다.**

| 파일 | 변경 |
|---|---|
| `.env.example` | `STORAGE_TYPE=minio` → `STORAGE_TYPE=local`. MinIO 변수는 남기고 선택형 Profile에서만 쓴다고 주석 수정. `JWT_SECRET` 생성 방법을 주석으로 안내 |
| `docker-compose.yml` | `minio` 서비스에 `profiles: ["minio"]` 추가 |
| `README.md` 빠른 실행 | 2단계에 `echo "JWT_SECRET=$(openssl rand -base64 32)" >> .env` 추가. 3단계 명령·제목, MinIO Health Check, 종료 명령에서 minio 제거. 경로 설명을 Local Filesystem으로 수정 |
| `README.md` 개요·기술 스택·환경 선택 | 기본 경로가 MinIO라고 설명하던 문장을 Local Filesystem 기본값, 선택형 MinIO로 정합화 |
| `README.md` 라이선스 표 | MinIO 행을 "선택형 Profile, 기본 설정에서는 기동하지 않음"과 2026-10-15 확인 결과로 수정 |

### 대안과 선택 이유

| 대안 | 판단 |
|---|---|
| MinIO 이미지를 릴리스 태그로 고정 | 공식 저장소 자체를 받을 수 없어 태그 고정으로 해결되지 않는다(#454). |
| 다른 S3 호환 이미지로 교체 | 새 이미지의 라이선스·호환성 검증이 필요하고 평가 직전 범위가 커진다. |
| compose에서 minio 서비스 삭제 | MinIO를 쓰는 팀원 로컬 환경과 MinIO 대상 E2E를 끊는다. Profile로 남기면 기존 사용자는 그대로 쓸 수 있다. |
| `.env.example`에 로컬 전용 `JWT_SECRET` 고정값 | `cp`만으로 실행되지만 저장소에 고정 서명 키가 공개된다. 명령 1줄을 늘리고 값을 로컬에서 생성한다. |
| `application-local.yml`에 `JWT_SECRET` 기본값 | 시크릿을 설정 파일에 하드코딩하지 않는다는 팀 규칙에 어긋난다. |

`JwtProvider`는 `JWT_SECRET`을 UTF-8 바이트 그대로 HMAC 키로 쓰므로 32바이트 이상이어야 한다.
`openssl rand -base64 32`는 44자를 만든다.

### MinIO를 계속 쓰는 경우

이미지를 이미 가진 환경은 `.env`에 `STORAGE_TYPE=minio`를 두고 다음처럼 명시적으로 기동한다.

```bash
docker compose --profile minio up -d minio
```

Compose는 명령줄에서 서비스 이름을 직접 지정하면 해당 Profile을 자동 활성화한다. 검증 VM에서
`docker compose --dry-run up -d --wait postgres minio valkey`가 minio Pull을 시도하는 것으로 확인했다.
README 「테스트」의 MinIO E2E 명령은 수정 없이 기존처럼 동작한다.

## 3. 비목표

- 저장소 Adapter 코드와 Spring 설정 변경
- MinIO 대상 개발용 E2E(`storageWorkerE2eTest`, `minioIntegrationTest` 등)의 이미지 수급 문제.
  새 PC에서는 이 명령도 minio Pull에서 실패한다.
- 기존 MinIO 저장 파일의 Local Filesystem 이전. 이미 `STORAGE_TYPE=minio`로 쌓은 로컬 데이터는 저장소
  설정만 바꾸면 기존 파일을 읽지 못하므로 기존 `.env`를 유지한다. `.env.example` 변경은 기존 `.env`에
  영향을 주지 않는다.

## 4. 검증

### 4.1 환경

- 호스트: Apple M5, 10 CPU, 24GB, macOS. Java 17.0.18, Node.js 26.5.0
- 새 PC 조건: 기존 Docker Desktop의 이미지·볼륨·Cache를 쓰지 않도록 Lima `docker-rootful` VM(6 CPU,
  8GiB, Docker 29.9.1 linux/arm64)을 새로 만들어 빈 Docker 데몬으로 사용했다. npm·Gradle Cache도 빈
  디렉터리로 지정했다. README 명령은 바꾸지 않고 `DOCKER_HOST`, `npm_config_cache`, `GRADLE_USER_HOME`
  환경 변수만 지정했다.
- 저장소: 별도 디렉터리에 `git clone https://github.com/DocGrid/docgrid.git`(`develop` `f84c732`) 후
  이 브랜치 변경을 적용했다.
- 회선: 측정 중 다운로드 속도가 약 2MB/s였다. 모델 다운로드 시간은 회선에 비례한다.

### 4.2 수정 전 재현

빈 데몬에서 `develop`의 README 3단계 명령을 그대로 실행했다.

```text
$ docker compose up -d --build postgres minio valkey embedding-server
 Image minio/minio Error pull access denied for minio/minio, repository does not exist or may require 'docker login'
 Image pgvector/pgvector:0.8.1-pg17 Interrupted
 Image valkey/valkey:9.1-alpine Interrupted
```

3초 만에 exit 1로 끝났고 컨테이너는 하나도 만들어지지 않았다.

### 4.3 수정 후 README 순서대로 실행

| 단계 | 명령·확인 | 결과 | 소요 시간 |
|---|---|---|---|
| 2 | `git clone` | 성공 | 33초 |
| 2 | `cp .env.example .env` 등, `npm --prefix frontend ci` | 성공(빈 npm Cache) | 2분 5초 |
| 2 | `echo "JWT_SECRET=..." >> .env` | 44자 값 생성 | 즉시 |
| 3 | `docker compose up -d --build postgres valkey embedding-server` | exit 0. 이미지 Pull·Embedding Image Build | 7분 7초 |
| 3 | `curl -f http://localhost:8000/health/ready` | `{"status":"ready"}`. BGE-M3 4.59GB 다운로드·로딩 포함 | `up` 시작부터 42분 31초 |
| 4 | `brew install ollama` / `brew services start ollama` | Ollama 0.40.4 | 85초 / 8초 |
| 4 | `ollama pull qwen2.5:7b` | 1차 1,491초 후 DNS 조회 실패로 중단, 재실행 시 이어받아 성공 | 2,110초(재실행분) |
| 4 | `ollama run qwen2.5:7b "안녕"` | 정상 응답, `ollama ps` `100% GPU` | 6초 |
| 5 | `./backend/gradlew -p backend bootRun` | `Started DocgridApplication`, `local` 프로필, Flyway 52개 적용, 인덱싱 Worker 등록 | 빈 Gradle Cache 3분 19초(배포판·의존성·컴파일), 앱 기동 4.92초 |
| 5 | `npm --prefix frontend run dev` | `http://localhost:3000` 200 | 7초 |
| 6 | 회원가입·로그인(UI) | 성공. seed 부서 3개 표시 | - |
| 6 | PDF 업로드(UI, 1쪽 한국어 PDF 22KB) | `UPLOADED`. 원본이 `./data/docgrid/documents/...pdf`에 저장 | - |
| 6 | 인덱싱 | `INDEXED`, Chunk 2개. Embedding Job 생성→완료 10초 | 클릭부터 18초 |
| 6 | AI 검색(UI) | 업로드 문서가 근거 [1], 유사도 81.1% | 즉시 |
| 6 | RAG 답변과 인용 | `SUCCESS`, qwen2.5:7b, 입력 543·출력 291 Token, 지연 13,452ms. 답변 내용이 문서와 일치 | 제출부터 17초 |
| 종료 | `docker compose stop postgres valkey embedding-server`, `brew services stop ollama` | 모두 정상 종료 | - |

`bootRun` 1차 실행은 수정 전 `.env`(`JWT_SECRET` 없음)로 Context 초기화에 실패했다. 2단계 명령을 추가한 뒤
같은 명령으로 기동했다. 2차 기동은 Gradle Cache가 채워진 상태라 대표값으로 쓰지 않는다.

검증 중 다른 도구가 호스트에서 Docker Desktop을 다시 시작해 같은 포트(55432, 8000)를 IPv6로 열었다.
검증 Backend가 VM의 서비스를 사용했는지 다음으로 확인했다.

- VM DB에 검증 계정, 업로드 문서, Embedding Job 1건, Worker 1건, RAG 응답이 있다. Flyway 적용 시각은
  검증 Backend 1차 기동 시각(22:39:21)이다.
- VM Embedding Server 로그에 업로드 인덱싱(`/embed/batch`, 22:44:45)과 검색 Query(`/embed`, 22:45:06) 요청이 있다.

### 4.4 남은 관찰

- RAG 답변 끝이 문장 중간에서 잘리고 "답변이 길어 일부 내용이 생략됐을 수 있습니다" 안내가 붙었다.
  출력 291 Token으로 `ollama.num-predict` 400과 `generate-deadline` 60초 모두에 못 미쳤다. 원인은 이
  이슈에서 조사하지 않았다.
- README 2단계는 "인덱싱 Worker는 기본값으로 꺼져 있습니다"라고 설명하지만 `application-local.yml`은
  `${INDEXING_WORKER_ENABLED:true}`이므로 `.env.example`의 `SPRING_PROFILES_ACTIVE=local`에서는 기본으로
  켜진다. 실행을 막지는 않아 이 이슈에서 수정하지 않았다.
- `embedding-server`는 모델을 받는 동안 로그가 `Waiting for application startup.`에서 진행 표시 없이 멈춰
  있고, `start_period` 900초가 지나면 `unhealthy`로 표시된다. 느린 회선에서는 실패로 오해할 수 있다.
