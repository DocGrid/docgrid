# 로컬 설정·준비 범위 계약 검증

## 목적

앱과 점검 도구가 서로 다른 설정을 사용하는 문제를 줄이고, 명시적 search 범위가 Ollama를 요구하지 않도록 한다.
로컬 주소뿐 아니라 실제 Compose 자원을 대조하는 계약도 검사한다.

## 테스트 환경

- 로컬 Python 3.9.6·Bash, 구현 `004768c`, 최종 시험 `c2a924a`.
- API·Docker 명령은 제어 대체물이다. 실제 Docker 기동·DB 접속·모델 호출은 0건이다.
- 원본 환경 파일이나 기존 실행 자원은 수정하지 않았다.

## 테스트 방법

```bash
python3 -m unittest discover -s scripts/demo -p 'test_*.py' -v
bash -n scripts/local-services.sh
```

1. 임시 환경 파일에서 치환·중복·인용 오류와 원격 DB·저장소·설정 우회를 검사한다.
2. 컨테이너 출처·포트 불일치·호스트 포트 단절·DB 인증/확장 결과를 제어한다.
3. 실행 파일 대체물로 실제 Bash start/status 분기를 실행해 search의 Ollama 호출 0과 기존 계약을 확인한다.
4. 이전 시연 판정 회귀도 함께 실행한다.

## 관측 결과

| 실행 ID | 목적 | 결과 | 근거 |
| --- | --- | --- | --- |
| demo-readiness-contract-01 | 초기 설정·범위 계약 | 51/51 통과 | [로그](demo-readiness-contract-01.log) |
| demo-readiness-shell-syntax-01 | Bash 문법 | 종료 코드 0 | [로그](demo-readiness-shell-syntax-01.log) |
| demo-readiness-contract-02 | context 우선순위·호스트 포트 보완 | 53/53 통과 | [로그](demo-readiness-contract-02.log) |
| demo-readiness-contract-03 | 기존 명령·Native 사전 준비 보완 | 55/55 통과 | [로그](demo-readiness-contract-03.log) |

search 제어 기동은 PostgreSQL·Valkey·BGE-M3만 요청했고 Ollama 주소 호출은 0건이었다.
Native 미준비 rag는 Compose up 이전에 중단했다. 이는 실제 모델·독립 환경의 E2E 결과가 아니다.

## 검증 과정에서 확인된 사항

- 기존 Bash는 자체적으로 `.env`를 읽지 않았고 Ollama를 항상 필수로 다뤘다.
- Docker CLI는 DOCKER_CONTEXT가 DOCKER_HOST보다 우선한다. 해당 순서와 이후 endpoint 고정을 별도 검사했다.
- 다른 작업공간의 컨테이너를 up 뒤에만 검사하면 늦을 수 있어, up 전 출처 확인과 --no-recreate를 적용했다.
- 컨테이너 내부 SQL만 성공하면 앱이 접속하는 호스트 포트 단절을 놓칠 수 있어 TCP 연결도 검사한다.
- PostgreSQL SELECT는 같은 사용자·비밀번호·SSL·search_path를 사용하고 비밀번호는 명령 인자로 넣지 않는다.
- 엄격한 설정 부분집합이므로 모호한 기존 파일은 자동 수정하지 않고 거부한다.
- 실제 서비스·원격 OpenSQL·Native Ollama·새 환경 시연은 이 회차에서 실행하지 않았다. 임시 제어 파일은 각 시험 종료 시 정리했다.
