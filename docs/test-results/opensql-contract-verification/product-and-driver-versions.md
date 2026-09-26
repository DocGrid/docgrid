# OpenSQL 3노드 제품·드라이버 버전 확인

## 무엇을 확인했나

Rocky Linux 9.7 `x86_64` 컨테이너 세 개에 설치된 OpenSQL·PostgreSQL·Patroni·etcd 버전, OpenProxy 두 개의 버전, 테스트 애플리케이션이 해석한 pgJDBC·HikariCP 버전을 기록했다. 버전이 같다는 사실은 이후 실험의 재현 조건이지, 제품 간 모든 호환성을 입증하는 결과는 아니다.

## 어디에서 어떤 명령을 실행했나

| 실행 위치 | 명령 또는 호출 | 목적 |
| --- | --- | --- |
| 개발자 컴퓨터의 저장소 루트 | `bash scripts/opensql/capture_live_ha_contract.sh` | 승인된 GCP 계정·프로젝트인지 먼저 검사하고 기존 세 VM의 컨테이너에서 버전만 수집한다. 실제 실행에는 `OPENSQL_GCP_ZONE`, `OPENSQL_SSH_KEY`, `OPENSQL_EXPECTED_ACCOUNT`, `OPENSQL_EXPECTED_PROJECT`를 환경 변수로 제공했다. 값 자체는 공개하지 않는다. |
| 스크립트가 SSH로 접근한 `node1/2/3`의 Rocky 컨테이너 | `python3 - collect --node nodeN` | [`capture_ha_contract.py`](../../../scripts/opensql/capture_ha_contract.py)의 `collect`가 설치된 실행 파일의 버전 출력을 허용 목록으로 가공한다. `node1`에는 OpenProxy가 없으므로 그 값은 `null`이다. |
| 개발자 컴퓨터의 저장소 루트 | `./backend/gradlew -p backend dependencyInsight --dependency org.postgresql:postgresql --configuration testRuntimeClasspath --offline` | Gradle 테스트 런타임이 실제로 선택한 pgJDBC 버전을 확인한다. 선언 버전만 읽는 것과 다르다. |
| 같은 위치 | `./backend/gradlew -p backend dependencyInsight --dependency com.zaxxer:HikariCP --configuration testRuntimeClasspath --offline` | HikariCP의 실제 선택 버전을 확인한다. |
| 개발자 컴퓨터에서 A/B OpenProxy로 연결한 JUnit | `./backend/gradlew -p backend openSqlOpenProxyContractTest --rerun-tasks --offline` | JDBC 메타데이터의 PostgreSQL 서버·드라이버 버전도 원격 연결을 통해 교차 확인한다. 연결 변수와 비밀번호는 셸 환경에만 주입했다. |

수집 스크립트 안의 SSH 명령 형태는 다음과 같다. `nodeN`은 `node1`부터 `node3`까지 반복되고 SSH 키 경로는 공개하지 않는다.

```bash
gcloud compute ssh "docgrid-nodeN" --zone="$OPENSQL_GCP_ZONE" \
  --ssh-key-file="$OPENSQL_SSH_KEY" \
  --command="sudo docker exec --user opensql -i docgrid-nodeN python3 - collect --node nodeN" --quiet \
  < scripts/opensql/capture_ha_contract.py
```

이는 수집 코드의 실행 구조를 보여주는 **비식별화한 표기**다. 실제 스크립트는 셸 변수로 노드명을 조합하고 결과를 임시 JSON에 받는다. 원본 라이선스나 설정 파일 전체를 출력하지 않는다.

## 어떤 코드가 결과를 만들었나

수집기는 설치된 바이너리의 버전 출력 중 제품명·버전만 파싱해 [node1](../opensql-contract-evidence/node1.json), [node2](../opensql-contract-evidence/node2.json), [node3](../opensql-contract-evidence/node3.json)의 `versions`에 넣는다. JDBC 테스트의 [`timeZoneBoundaryIsStableAcrossProxies()`](../../../backend/src/test/java/com/opensource/docgrid/opensql/OpenSqlOpenProxyContractTest.java)는 `connection.getMetaData().getDatabaseProductVersion()`과 `getDriverVersion()`을 각각 A/B에서 읽어 `CONTRACT_SERVER`, `CONTRACT_DRIVER` 관측값을 남긴다. 이 테스트는 `DriverManager`로 연결하므로 HikariCP를 실제로 구동하는 시험은 아니다.

## 실제 결과와 해석

| 대상 | 관측값 | 의미 |
| --- | --- | --- |
| 세 컨테이너 | Rocky Linux 9.7, `x86_64` | 세 노드의 OS 식별자·아키텍처가 동일했다. |
| OpenSQL | 세 노드 모두 `v3.17.8.7` | 제품 버전 차이로 인한 노드별 동작 차이를 이번 기준선에서는 배제할 수 있다. |
| OpenProxy | `node2/3` 모두 `1.1.3`, revision `723`; `node1`은 미설치 | 프록시 두 대의 설치 빌드가 같았다. |
| Patroni / etcd | 각각 세 노드 모두 `4.0.5` / `3.6.5` | 이후 리더 선출 시험에서 같은 버전의 3멤버를 대상으로 한다. |
| PostgreSQL 실행 파일 / psql | 세 노드 모두 `17.8` / `17.8` | 설치 바이너리와 클라이언트 버전이다. |
| JDBC 서버 메타데이터 | A/B 모두 PostgreSQL `17.8` | 실제 프록시 경유 연결이 반환한 서버 버전이다. |
| pgJDBC / HikariCP | `42.7.11` / `6.3.3` | Gradle `testRuntimeClasspath`의 선택 버전이다. pgJDBC `42.7.11`은 JDBC 메타데이터와도 일치했다. |

원본은 [통합 스냅샷](../opensql-contract-evidence/contract-manifest.json)과 [JUnit 요약](../opensql-contract-evidence/junit-summary.json)이다. JUnit 전체 5개 시험은 2026-09-26 12:46 UTC에 실패 0건이었고, 이 문서의 버전 항목은 그 시험과 별도 수집 스냅샷을 함께 해석한 것이다. 수집은 12:57:23~12:58:14 UTC에 순차 실행됐으므로 두 파일을 **동일 시점의 원자적 상태**라고 표현하지 않는다.

## 결론과 한계

버전 기록 기준은 완료됐다. 하지만 HikariCP `6.3.3`은 의존성 확인 결과이지 이 계약 JUnit에서 Hikari 풀을 실행했다는 뜻은 아니다. 또한 `node1`의 OpenProxy 값이 `null`인 것은 수집 누락이 아니라 해당 노드에 프록시를 설치하지 않은 토폴로지다. 전체 Gradle 테스트 `1,124개 중 104개 실패`라는 별도 실행 결과도 있으므로 “저장소 전체 테스트 성공”으로 확대하지 않는다.
