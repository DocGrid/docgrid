# 로컬 시연 설정과 준비 점검

## 목적

명시적 search/rag 범위는 앱이 사용할 설정과 실제 로컬 Compose 자원을 대조한다.
기존 인자 없는 `local-services.sh start/status`의 Native Ollama 기동·필수 판정은 유지한다.

## 설정 해석

- 예제의 `KEY=VALUE` 형식만 허용한다. `.env`를 source/eval하지 않는다.
- 환경변수는 파일 값보다 우선한다. 셸 치환·모호한 따옴표·이스케이프·중복 키는 거부한다.
- local 프로필·Local Filesystem·IPv4 로컬 DB/Valkey만 지원한다. DB/Valkey의 localhost는 자식 환경에서도 127.0.0.1로 확정한다.
- 원본 경로는 저장소 `data` 또는 `.demo/storage` 아래로 제한한다. 원격 OpenSQL·GCS·Spring/JVM 설정 우회는 이 경로에서 거부한다.
- 프런트엔드 파일과 프로세스 환경에서 읽은 API·WS 주소도 로컬 API 8080과 맞는지 검사한다.
- 개인 환경 파일은 읽기만 하며 자동 수정하지 않는다. 지원되지 않는 파일 문법은 명확히 거부한다.

## Docker·DB 대조

Docker CLI의 DOCKER_CONTEXT 우선순위에 맞춰 실제 endpoint를 확인한다. Unix 소켓만 허용하고 이후 자식 명령은
그 endpoint에 고정해 전역 context 변경의 영향을 줄인다. [공식 CLI 환경변수 문서](https://docs.docker.com/reference/cli/docker/).

```text
설정 해석 → 로컬 endpoint 확인
  → up 전에 기존 컨테이너의 서비스·작업 디렉터리 확인
  → --no-recreate로 대상 서비스 기동
  → 컨테이너 ID·포트·DB 초기 설정 대조
  → 앱이 사용할 호스트 TCP 포트 연결 확인
  → 같은 DB 계정·SSL·search_path로 SELECT만 실행
  → pgvector 0.8.1 사용 가능 여부 확인
```

단순 localhost 판정만으로 SSH 터널과 실제 로컬 DB를 구분했다고 주장하지 않는다.
DB 점검은 Compose 식별·바인딩·연결·인증·vector를 함께 확인한다. pg_trgm은 Flyway 마이그레이션 뒤 준비되는 항목이다.

## 서비스 범위

| 호출 | Compose | Ollama |
| --- | --- | --- |
| 인자 없는 기존 start/status | 기존 Valkey·BGE-M3 계약 | 기존 Native 기동·필수 판정 유지 |
| `start/status --scope search` | PostgreSQL·Valkey·BGE-M3 | 기동·API 호출·필수 판정 모두 생략 |
| `start/status --scope rag` | 같은 3개 서비스 | 사전 기동된 Native API·모델 재사용. 서비스 관리자·앱을 자동 기동하지 않음 |

명시적 rag의 Native 서버가 준비되지 않았으면 Compose 기동 전에 실패한다.
실제 생성 모델 답변 시험은 준비 상태 확인과 별개다.
