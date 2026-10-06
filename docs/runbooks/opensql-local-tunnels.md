# 로컬 백엔드용 OpenSQL 3노드 SSH 터널

## 연결 구조와 범위

macOS 사용자는 `scripts/opensql/local_tunnels.py`를 한 번 설치해 로그인 후 IAP SSH 터널을
자동으로 시작할 수 있다. 사용자별 `launchd` Agent 세 개가 각각 DB VM 하나에 접속한다.
SSH 연결이 종료되면 `launchd`가 재시작한다. Mac이 꺼져 있거나 GCP 인증·권한이 만료된 동안에는
터널을 유지할 수 없다. 이 도우미는 GCP VM이나 저장소의 DB 설정을 변경하지 않는다.

```text
로컬 Spring Boot → OpenProxy A/B 터널(node2/node3) → OpenSQL primary/standby
로컬 Flyway     → DB node1/2/3 터널 → 현재 primary 탐색
```

현재 배치에서 OpenProxy A는 node2, B는 node3에 있다. 도우미는 저장소 루트 `.env`의
`OPENSQL_APP_JDBC_URL`에 적힌 루프백 포트 두 개와 `OPENSQL_MIGRATION_JDBC_URL`의
루프백 포트 세 개를 **나열된 순서대로** 위 노드에 연결한다. 로컬 포트 번호를 바꾸면
`install`을 다시 실행해야 한다. DB 접속 암호·JWT·SSH 개인키는 터널 설정으로 저장하거나
출력하지 않는다.

## 팀원별 일회성 준비

1. 자신의 Google Cloud 계정에 대상 프로젝트의 IAP SSH 접근 권한을 받고, Google Cloud CLI와
   Python 3.9 이상을 설치한다. `gcloud auth login`으로 **본인 계정**에 로그인한다. SSH 키 생성이나
   OS Login 설정이 필요한 경우 GCP 담당자가 승인한 접속 절차로 VM별 SSH를 먼저 확인한다.
   macOS에서 gcloud SSH 개인키에 암호 문구가 있으면 본인 터미널에서 다음 명령으로 Keychain에
   저장한다. 기본 키 대신 별도 키를 사용한다면 그 키 경로로 바꾼다. 암호 문구는 화면 공유나
   채팅에 입력하지 않는다.

   ```bash
   ssh-add --apple-use-keychain ~/.ssh/google_compute_engine
   ```

   터널 도우미의 SSH는 `UseKeychain=yes`와 `BatchMode=yes`로 실행되므로, Keychain에 저장되지
   않은 암호 문구를 실행 중에 물어볼 수 없다.
2. 각자 저장소 루트의 Git 제외 `.env`를 준비한다. `SPRING_PROFILES_ACTIVE=opensql-ha`,
   앱 계정·Flyway 계정의 OpenSQL 환경 변수와 127.0.0.1의 JDBC 터널 주소가 필요하다.
   암호와 VM 식별자를 팀 채팅이나 저장소에 공유하지 않는다.

   ```text
   SPRING_PROFILES_ACTIVE=opensql-ha
   OPENSQL_APP_JDBC_URL=jdbc:postgresql://127.0.0.1:16432,127.0.0.1:16433/docgrid?currentSchema=public&sslmode=disable&connectTimeout=3&loadBalanceHosts=true
   OPENSQL_MIGRATION_JDBC_URL=jdbc:postgresql://127.0.0.1:15431,127.0.0.1:15432,127.0.0.1:15433/docgrid?currentSchema=public&sslmode=disable&targetServerType=primary&connectTimeout=3
   OPENSQL_APP_USER=<승인된 앱 계정>
   OPENSQL_APP_PASSWORD=<개인 비밀값>
   OPENSQL_MIGRATION_USER=<승인된 마이그레이션 계정>
   OPENSQL_MIGRATION_PASSWORD=<개인 비밀값>
   ```

   위 URL은 기본 로컬 포트의 예시다. 실제 DB 이름·TLS·타임아웃 설정은 팀에서 승인한
   연결 값을 사용한다. GCS 저장소를 쓰면 별도로 본인 계정의 Application Default Credentials도
   준비한다.
3. GCP 콘솔의 **Compute Engine → VM 인스턴스**에서 프로젝트 ID, 영역, DB node1/2/3의
   VM 이름을 확인한다. 팀원이 각자 다음 명령을 저장소 루트에서 실행해 자신의 값만 입력한다.

   ```bash
   python3 scripts/opensql/local_tunnels.py configure
   ```

   개인 설정은 사용자 홈의 `.config/docgrid/opensql-tunnels.json`에 `0600` 권한으로 저장된다.
   프로젝트·영역·VM 이름만 포함하며 DB 암호와 토큰은 포함하지 않는다. VM 배치가 바뀌면 이
   개인 설정을 직접 수정하고 `install`을 다시 실행한다.

## 설치와 사용

```bash
# 로그인 시 자동 시작할 Agent 세 개를 설치하고 지금도 실행한다.
python3 scripts/opensql/local_tunnels.py install

# 각 Agent 등록 상태와 로컬 포트 청취 상태를 확인한다.
python3 scripts/opensql/local_tunnels.py status

# 이번 로그인 세션에서만 중지하거나 다시 시작한다.
python3 scripts/opensql/local_tunnels.py stop
python3 scripts/opensql/local_tunnels.py start

# 자동 시작 설정을 제거한다. 개인 VM 설정은 남겨 둔다.
python3 scripts/opensql/local_tunnels.py uninstall
```

`install`은 세 Agent를 등록한다. 실제 연결 성공은 `status`에서 세 Agent가 `등록`으로,
`.env`의 로컬 포트 다섯 개가 `열림`으로 나오는지 확인한다. 하나라도 닫혀 있으면 `status`가
종료 코드 1을 반환한다. 포트 청취는 SSH 전달 소켓이 열린 상태만 뜻하며 DB 인증·SQL 성공을
증명하지 않는다.
그다음 `./backend/gradlew -p backend bootRun`을 실행한다. IntelliJ에서 직접 백엔드를
시작한다면 작업 디렉터리를 저장소 루트로 맞춰 `.env`를 읽게 한다.

`.env`의 포트, 개인 설정, Python 또는 `gcloud` 설치 경로를 바꿨다면 `install`을 다시 실행한다.
저장소 자체를 이동했다면 Agent가 예전 스크립트 경로를 가리키므로 새 위치에서 재설치한다.
`stop`은 다음 로그인 때의 자동 시작 설정을 남긴다. 자동 시작까지 없애려면 `uninstall`한다.

## 연결되지 않을 때

| 관측 | 확인할 항목 |
| --- | --- |
| `configure`가 기존 설정을 거부 | 개인 설정 파일의 값·권한을 확인하고 수정한 뒤 `install` 재실행 |
| `status`에서 Agent `미등록` | `install` 재실행, macOS 사용자 로그인 세션과 `launchctl` 상태 확인 |
| Agent `등록`이지만 포트 `닫힘` | 본인 GCP 인증·IAP/SSH 권한, VM 실행 상태, 로컬 포트 충돌과 SSH 키의 Keychain 저장 여부 확인 |
| 포트 `열림`인데 원격 서비스 응답이 없음 | `status`는 청취만 확인한다. 해당 VM의 DB·OpenProxy 상태를 확인하고 `install`로 터널을 다시 연결한 뒤 재확인 |
| 다섯 포트 `열림`이지만 백엔드 DB 오류 | 앱·Flyway 자격증명, 원격 서비스 상태, 현재 primary와 Flyway 마이그레이션 결과 확인 |

자동 Agent는 `gcloud` 출력을 파일에 남기지 않는다. 진단이 필요하면 `stop` 후
`python3 scripts/opensql/local_tunnels.py serve node2`처럼 한 노드를 터미널에서 직접
실행해 오류를 확인한다. 공유할 때는 프로젝트 ID, VM 이름, 주소, 계정, 키·토큰을 먼저 가린다.
`Ctrl+C`로 진단용 연결을 끝낸 뒤 `start`로 자동 Agent를 되살린다.

로컬 앱의 일반 JDBC는 두 OpenProxy 터널만 사용하지만, 현재 `opensql-ha` 프로필은 Flyway가
활성화돼 있어 기동 시 DB 세 노드의 직접 터널도 필요하다. Flyway는 별도 마이그레이션 계정으로
현재 primary에 접속하며, 미적용 마이그레이션이 있으면 원격 DB 스키마를 변경한다.
