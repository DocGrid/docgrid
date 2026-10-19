# DocGrid Backend

Spring Boot API·Worker와 임베딩 서버를 관리합니다. 명령은 **저장소 루트**에서 실행합니다.
Web을 포함한 시작·검증·종료는 [통합 실행](../README.md#빠른-실행)을 사용하고, 아래는 Backend 단독 실행 절차입니다.

## 로컬 설정과 필수 서명 키

Java 17·Docker Engine·Compose·OpenSSL·curl을 준비합니다. `.env`가 없을 때만 예제 파일을 복사합니다.

```bash
test -f .env || cp .env.example .env
```

`JWT_SECRET`은 기본값이 없는 필수 값입니다. `.env`에 이미 설정한 키는 유지합니다. 키가 없으면 아래 명령으로 생성한 값을
`.env`의 `JWT_SECRET=<생성한 값>`에 저장한 뒤 Application을 시작합니다. 키를 저장소·로그·Issue에 공유하지 않습니다.

```bash
openssl rand -base64 32
```

예제는 `SPRING_PROFILES_ACTIVE=local`, `STORAGE_TYPE=local`입니다. 원격 OpenSQL·GCS를 가리키는 개인 설정을
로컬 예제로 덮어쓰지 않습니다. 원격 연결은 [환경 선택](../README.md#환경-선택)을 따릅니다.
`bootRun`은 저장소 루트를 작업 디렉터리로 사용합니다.

## Local DB

기본 이미지는 PostgreSQL 17·pgvector 0.8.1을 제공하는 `pgvector/pgvector:0.8.1-pg17`입니다.
DB와 인증·권한 캐시용 Valkey를 준비한 뒤 Backend를 기동합니다.

```bash
docker compose pull postgres valkey
docker compose up -d --wait --wait-timeout 60 postgres valkey
./backend/gradlew -p backend bootRun --args='--spring.profiles.active=local'
```

DB 기본 주소는 `localhost:55432`, database·user는 `app`, `DB_SSLMODE=disable`입니다. 실제 접속값은 실행 설정을 사용합니다.
PostgreSQL 17의 `docgrid_postgres17_data` 볼륨은 과거 `opensql_data`와 별개이며 자동 삭제하지 않습니다.
[DB 상세 절차](../docs/local-db.md)

이 단계는 Backend·DB·캐시 기동입니다. 업로드 인덱싱과 검색에는 아래 임베딩 서버도 필요합니다.
`local` 프로필의 인덱싱 Worker 기본값은 `true`, 다른 프로필은 `false`입니다.

## 임베딩 서버

문서·질문을 벡터로 변환하는 FastAPI·BGE-M3 서버입니다. 별도 Terminal에서 준비합니다.

```bash
docker compose up -d --build embedding-server
docker compose logs -f embedding-server
```

첫 실행은 모델 다운로드·로딩이 필요하며 시간은 환경에 따라 다릅니다. 로그 조회는 `Ctrl+C`로 끝내고 readiness를 확인합니다.

```bash
curl -f http://localhost:8000/health/ready
```

로딩 중에는 readiness가 503이며 준비되면 200입니다. 기본 주소는 `http://localhost:8000`, 변경 변수는 `EMBEDDING_SERVER_URL`입니다.
의존 서비스의 준비·설정 점검은 `./scripts/local-services.sh start --scope search`와 `status --scope search`로도 실행할 수 있습니다.
이 명령은 Backend·Frontend를 시작하는 통합 명령과 구분합니다.

## Ollama (RAG LLM 서버)

생성 답변까지 확인할 때는 Ollama와 지정 모델 `qwen2.5:7b`를 준비합니다. 아래는 macOS Native 실행 예입니다.
검색 전용 독립 재현 시험은 실제 Ollama를 기동하지 않았습니다. 모델·장비·프롬프트에 따라 추론 시간이 달라집니다.

```bash
brew install ollama
brew services start ollama
ollama pull qwen2.5:7b
ollama run qwen2.5:7b "안녕"
```

- `ollama pull`은 최초 다운로드와 모델 변경 시 필요합니다. `ollama ps`로 실행 상태와 CPU/GPU 사용을 확인합니다.
- 기본 주소는 `http://localhost:11434`, 변경 변수는 `OLLAMA_SERVER_URL`입니다.
- Ollama 연결 실패 시 RAG 생성은 실패하며 현재 허용 근거의 원문 인용 대체 답변을 표시합니다.
- `./scripts/demo.py start --scope rag`는 사전 기동한 Native Ollama·모델을 확인하고 `verify`에 범위를 이어줍니다.
- Homebrew 서비스 중지는 `brew services stop ollama`입니다. 통합 명령은 Native Ollama를 자동 종료하지 않습니다.

## 종료와 검증

수동 실행 Backend는 해당 Terminal에서 `Ctrl+C`, 직접 시작한 의존 서비스는 다음 명령으로 중지합니다. 볼륨은 유지합니다.

```bash
docker compose stop postgres valkey embedding-server
```

통합 명령으로 시작했다면 `./scripts/demo.py stop`으로 해당 세션의 소유 자원만 종료합니다.
기본 회귀·권한 HTTP·저장소 E2E의 환경과 명령은 [테스트 안내](../README.md#테스트)를 참고하세요.
