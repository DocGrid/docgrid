# 로컬 시연 통합 명령

## 목적과 경계

`scripts/demo.py`가 기존 Compose·서비스 스크립트·Gradle·npm·예제 검증기를 연결한다.
업무 API나 인덱싱 구현을 새로 만들지 않는다. macOS·Linux의 POSIX 프로세스 그룹을 사용하며 Windows는 지원하지 않는다.
기본 범위는 search이고, Native Ollama가 이미 준비된 경우에만 start의 `--scope rag`를 선택한다.

## 사용자 명령

```bash
./scripts/demo.py start
./scripts/demo.py verify
./scripts/demo.py status
./scripts/demo.py stop
```

Python 3.9 이상, Java 17, Node.js 22.13 이상, Docker Compose, Git·Bash·curl·lsof·ps가 필요하다.
처음에는 없는 환경 파일만 예제에서 만들고 JWT 서명 키를 무작위 생성한다. 기존 파일은 수정하지 않는다.
node_modules가 없으면 npm ci를 연결하고, 이미 있으면 npm ls로 확인한다. 맞지 않는 기존 의존성은 자동 교체하지 않는다.

## 기동과 검증

```text
사용자 start
  → 명령 잠금 획득: 다른 start/verify/stop과 동시 변경 거부
  → LocalConfig: local·파일 저장소·로컬 DB·고정 API/Web 포트 확인
  → 시작 전 컨테이너 ID와 실행 상태, Docker Engine ID 기록
  → local-services.sh start --scope search
  → 전용 프로세스 그룹에서 Gradle bootRun
       └ readiness UP + API/관리 포트의 실제 수신 프로세스 대조
  → 전용 그룹에서 npm run dev
       └ 로그인 HTML + 직접/프록시 departments 결과 대조
  → READY 기록

사용자 verify
  → 설정·소스 fingerprint, 서버 소유권, 컨테이너 ID, DB 재확인
  → 프런트엔드 /api/backend 프록시로 기존 예제 검증기 실행
  → 업로드·INDEXED·기대 검색·다운로드 해시 판정
  → 같은 DB 컨텍스트의 이전 원장과 문서 ID 대조
  → 매 실행을 별도 디렉터리에 기록
```

search는 Ollama를 기동·필수 확인하지 않는다. 백엔드 자체 RAG Worker의 동작을 비활성화하는 설정은 아니며,
Ollama가 없으면 생성 답변은 실패 대체 경로를 사용한다. search 검증은 생성 답변을 미실행으로 표시한다.

## 종료·취소와 보존

```text
stop 또는 start 도중 취소
  → PID + 시작 시각 + 명령 fingerprint + 프로세스 그룹 대조
  → 부모 계보로 등록한 분리 자식 JVM의 현재 식별정보 재조회
  → 등록된 자식 PID와 소유 그룹 TERM → 확인된 잔존 자식만 필요시 KILL
  → 저장된 Docker endpoint·Engine·컨테이너 ID·Compose 작업공간 대조
  → 이번 실행이 시작한 서비스만 stop
  → API·관리·Web 포트 해제 확인
  → STOPPED 또는 NEEDS_ATTENTION 기록

처음부터 실행 중이던 Compose → 재사용 후 유지
Native Ollama → 외부 관리 서버로 유지
DB 볼륨·원본·모델 캐시·이전 실행 기록 → 보존
식별자가 다르거나 소유권 불명 → 자동 종료 거부, 조치 필요 표시
```

자식 supervisor가 먼저 PID 파일을 기록해 부모 상태 저장 중단에도 회수할 수 있게 한다.
Gradle의 단발성 Daemon은 별도 프로세스 그룹을 만들 수 있다. 따라서 supervisor는 살아 있는 부모 계보에서
확인한 자식의 PID·시작 시각·명령 fingerprint를 주기적으로 기록한다. 실제 listener가 이 기록과 일치하는지도
확인하며, 분리된 그룹 전체를 임의로 소유한다고 간주하지 않는다. 종료 직전에도 계보를 재조회한다.
기동 중 중단된 세션은 컨테이너 전후 상태도 다시 대조한다. 같은 PID만으로 종료하지 않는다.
READY 기록이 있어도 status는 실제 준비 상태를 다시 검사하며, 검증 실패 후에는 서버를 남겨 원인을 확인할 수 있다.

## 기록과 제한

- `.demo/launcher/sessions/<id>/`: 정제된 구성요소 로그·상태·이벤트. 운영 상태 JSON은 원자 교체한다.
- `.demo/reports/<id>/`: 예제 검증의 불변 실행별 원장. 반복 검증은 이전 로그를 덮어쓰지 않는다.
- 비밀값·이메일·URL은 supervisor에서 파일 기록 전에 마스킹한다. 환경 원문은 로그에 저장하지 않는다.
- 원격 OpenSQL·GCS나 다른 작업공간 Compose는 자동 수정하지 않고 거부한다.
- 프로세스 소유권·주소 검증은 로컬 시연 오작동 방지이며 악의적인 로컬 관리자에 대한 보안 격리는 아니다.
- 실제 모델·새 Docker 환경의 E2E는 별도 실행 기록으로 확인한다. 이 설계 자체는 실행 증거가 아니다.
