# OpenSQL 3노드 실행·라이선스 경로 검증 — 2026-09-23

## 결과와 검증 범위

Google Cloud의 Rocky Linux 9.7 `x86_64` 컨테이너 3개에 OpenSQL을 설치하고 실행했다. 각 PostgreSQL 프로세스에 해당 노드의 `OPENSQL_LICENSE_PATH`가 전달됐고, `opensql_license` 모듈을 로드한 상태에서 SQL 접속과 쿼리가 성공했다. 앞선 [라이선스 배치 검증](opensql-three-node-license-installation-20260923.md)의 후속 단계다.

이 결과는 **라이선스 경로 전달·모듈 로드·DBMS 실행 및 쿼리 처리**를 확인한 것이다. 공급사의 별도 라이선스 상태 조회 명령이나 만료·재부팅 상황까지 검증했다는 뜻은 아니다. 공개 문서에는 프로젝트 ID, 실제 호스트명·사설 IP, 운영 파일 경로, 라이선스 원본과 해시를 기재하지 않는다.

## 설치 구성

| 논리 노드 | PostgreSQL 역할 | OpenProxy |
|---|---|---|
| node1 | 리더 | 공급사의 3노드 구성에 따라 미설치 |
| node2 | 스트리밍 복제 노드 | 실행 확인 |
| node3 | 스트리밍 복제 노드 | 실행 확인 |

세 VM은 동일한 `e2-standard-2` 유형으로 실행 중이었다. 각 컨테이너의 `lscpu`에는 논리 CPU 2개, 소켓 1개, 소켓당 코어 1개, 코어당 스레드 2개가 표시됐고, 메모리 제한은 6 GiB였다. 컨테이너 OS는 Rocky Linux 9.7 (Blue Onyx), `x86_64`였다. 필요한 추가 RPM 의존성은 Rocky 9.7 Vault 저장소에서 설치했다.

설치한 OpenSQL 패키지는 `Tmax_OpenSQL_3.17.8.7_rockylinux9.7_buildtime20260720`이며 실행 중인 데이터베이스는 PostgreSQL 17.8로 보고했다. PostgreSQL, pgvector, etcd, Patroni, OpenProxy, O2 라이선스 모듈 중 이 구성에 필요한 항목을 설치했다. 선택 항목인 `pgvectorscale`과 무관한 확장은 설치하지 않았다.

## 라이선스 실행 경로 확인

- 세 노드에서 실행 중인 PostgreSQL 프로세스의 `OPENSQL_LICENSE_PATH`가 각 노드에 배치한 라이선스를 가리키는지 확인했다. 원본과 설치본의 SHA-256도 노드별로 대조했지만, 해시값은 공개하지 않는다.
- 각 라이선스의 호스트 바인딩 값은 해당 컨테이너의 실제 호스트명과 일치했고 CPU 제한은 논리 CPU 2개였다. 검증 당시 체험판 유효 기간 안에 있었다.
- 설치 후 라이선스 파일의 소유자·권한은 `opensql:opensql`, `0640`이었다. 모든 노드에서 `SHOW shared_preload_libraries` 결과에 `opensql_license`와 `pg_stat_statements`가 포함됐고, SQL 쿼리가 성공했다.

## 데이터베이스·클러스터 검증

- etcd 엔드포인트 3개 모두 상태 검사 요청을 정상 처리했다.
- Patroni는 node1을 `leader/running`, node2·3을 `replica/streaming`으로 보고했고 당시 복제 지연은 `0`이었다.
- 리더의 `pg_stat_replication`에서 두 복제 노드가 모두 `streaming` 상태였고, 복제 방식은 비동기였다.
- 세 노드의 로컬 SQL 쿼리가 성공했다. `pg_is_in_recovery()`는 node1에서 `false`, node2·3에서 `true`였다.
- `opensql` 데이터베이스에 `vector` 0.8.1 확장을 생성했다. 모든 노드에서 코사인 거리 계산 결과가 `1`로 나와 쿼리 실행과 확장 복제를 확인했다.
- node2·3의 OpenProxy를 통한 인증 SQL 접속은 리더에 도달했다. 당시 공급사 기본 프록시 풀은 `postgres` 데이터베이스로 연결됐으며, **DocGrid 애플리케이션의 접속 대상은 아직 이 클러스터로 전환하지 않았다.**

## 자동 시작·접근 통제

각 VM에서 컨테이너 실행 후 etcd와 Patroni를 시작하고 node2·3에서는 OpenProxy도 시작하는 systemd 유닛을 활성화했다. 유닛 재실행 시 중복 프로세스가 생기지 않음을 확인했다. **VM 전체 재부팅과 장애 조치(failover)는 시험하지 않았다.**

슈퍼유저·복제·되감기용 암호는 VM별 root 전용 파일에 보관했고, 파일 권한은 `0600`이었다. Patroni와 OpenProxy의 인증 설정 파일도 실행 계정만 읽을 수 있도록 `0600`으로 제한했다. 이 문서나 저장소에는 암호를 기록하지 않았다. 데이터베이스 접근은 내부 네트워크 규칙으로 제한했으며, PostgreSQL의 공개 접근 규칙은 추가하지 않았다.

## 설치 중 해결한 문제와 남은 범위

- 최소 구성 Rocky 컨테이너에 `sudo`와 `ps`가 없어 추가했다. 처음에는 `ps`가 없어 공급사의 etcd 시작 스크립트가 실패를 보고했지만, 당시 세 etcd 프로세스는 정상 동작 중이었다.
- 공급사 설치 프로그램이 OpenProxy 실행 파일을 `root:root`, `0700`으로 복사해, 프록시 실행 전에 `opensql:opensql`, `0750`으로 수정했다.
- VM의 SELinux가 사용자 정의 디렉터리 아래 부트스트랩 스크립트의 systemd 실행을 차단했다. 스크립트를 표준 실행 경로로 옮겨 `bin_t` 컨텍스트를 적용했다.

구성 요소 설치·설정 재개 중 위 문제로 설치 프로그램이 0이 아닌 종료 코드를 반환했으나, 수정 후 이 문서에 명시한 실행 검증 항목은 통과했다. 작업 후 설치 프로그램 작업 사본·전송 압축 파일·임시 인증 정보·작업용 SSH 키를 정리해 VM당 약 2 GiB를 확보했다. 사용자 로컬의 원본 압축 파일은 그대로 두었다.

이 문서는 **초기 실행과 라이선스 경로 검증 결과**다. 전체 VM 재부팅, 자동 장애 조치, DocGrid 애플리케이션·Flyway 연결, 실제 인덱싱과 권한 검색은 별도 검증 대상이다.
