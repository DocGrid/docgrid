# RAG 관측 코드 계약 검증 — `rag479-local-contract-r1`

- 목적: Ollama 결과별 호출·시간·토큰 지표와 BGE `/metrics` 접근 제한이 기존 응답 계약을 깨지 않는지 확인한다.
- 실행 위치·개정: 로컬 격리 작업 트리 `feat/479`, Java 17/Gradle 및 기존 BGE 런타임 Docker 이미지.
- 성공 기준: 관련 Java·Python 테스트 모두 통과, Prometheus 설정 문법 통과, 민감한 프롬프트·답변·사용자 ID가 새 레이블에 없음.

| 명령·방법 | 결과 요약 | 판정 |
| --- | --- | --- |
| `./backend/gradlew -p backend test --tests '*OllamaClientTest' --tests '*OllamaClientWireContractTest' --no-daemon` | Java **16건 통과**, 실패 0건 | complete·partial·failure 호출과 시간, 제공된 토큰 값 기록 검증 |
| 기존 BGE 이미지에 수정한 `main.py`·`test_main.py`를 읽기 전용 마운트하고 `python -m pytest -q test_main.py` | Python **23건 통과**, 경고 2건 | `/metrics` 비허용 403·허용 200과 기존 임베딩 계약 통과 |
| `docker run --rm --entrypoint=promtool -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" prom/prometheus:v3.5.5 check config /etc/prometheus/prometheus.yml` | config **성공**, 규칙 3개 파일·20개 규칙 확인 | file-discovery 추가 후 문법 통과 |
| `./backend/gradlew -p backend bootJar --no-daemon` | JAR 생성 성공, SHA-256 `153bb7f1cb343784112e3b6652955870e84d18361fd56521e2228b0e25144120` | GCP A/B에 동일 파일 적용 |

첫 로컬 `python3 -m pytest`는 호스트 Python에 pytest가 없어 종료 코드 1이었다. 시험 코드를 변경한 실패가 아니라 실행 환경 의존성 문제였고, 기존 BGE 이미지 안에서 pytest를 설치해 재실행했다. 이어서 기존 이미지의 TestClient가 `client=` 인자를 지원하지 않아 최초 단독 점검이 `TypeError`로 실패했다. 테스트는 해당 런타임이 지원하는 기본 클라이언트로 수정했고 최종 23건이 통과했다. 이 파일은 시험 도구 출력에서 확인한 비식별 요약이며 실시간 raw 터미널 로그 파일은 아니다.
