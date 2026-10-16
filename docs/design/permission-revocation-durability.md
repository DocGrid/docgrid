# 문서 단건 권한 회수의 동기 확정과 최신 원장 인가

## 적용 경계

공개 클러스터의 현재 비동기 정책을 이 코드 변경으로 바꾸지 않습니다. 동기 회수는 `permission.revocation.synchronous-enabled=true`로 명시적으로 활성화하며, **Patroni strict·동기 후보 1대 이상을 검증한 격리 클러스터**를 전제로 합니다. 기본값 false는 기존 API 계약을 보존합니다. `synchronous_commit=on`만으로 원격 내구성이 생기지 않습니다.

## 확정 절차

1. 문서 회수 요청은 UUID `Idempotency-Key`를 사용합니다. 프런트엔드는 같은 문서·권한의 실패 재요청에서 키를 유지합니다.
2. 별도 짧은 JPA 트랜잭션의 같은 물리 연결에서 Primary, 동기 후보 설정을 확인하고 `SET LOCAL synchronous_commit=on`을 적용합니다.
3. 키를 actor/document/permission에 묶은 요청 행을 잠그고 현재 ADMIN을 검증합니다. 최초 요청은 기존 회수 Command를 실행합니다. 같은 키 재요청은 회수 상태를 다시 주장하고 캐시를 무효화합니다.
4. 요청 행의 횟수·시각을 실제 UPDATE해 **새 WAL**을 생성합니다. DELETE 0행만으로 확정하지 않습니다. 권한 삭제·캐시·Outbox·요청 행은 같은 트랜잭션입니다.
5. 활성화된 경로의 실제 COMMIT에서만 물리 연결의 경고를 Hikari 정리 전에 읽습니다. COMMIT 경고나 접속 오류는 결과 불명 503이며, 같은 키로 재요청합니다. 정상 COMMIT만 204입니다. SyncRep 대기는 statement_timeout만으로 종료되지 않은 실제 실행을 확인했으므로, COMMIT 구간에 JDBC 네트워크 제한 5초를 적용하고 원래 값을 복원합니다. 연결 단절은 롤백 확정이 아닙니다.

## 주요 제한

- strict는 DB SQL로 증명할 수 없는 Patroni 운영 전제입니다. 동기 후보가 빈 SQL 설정은 거부하지만, non-strict 구성에 대한 보장을 만들지 않습니다. 운영 도입 시 REST/DCS 및 실제 SQL 적용을 함께 검증해야 합니다.
- 동기 standby 전체 상실은 strict에서 쓰기 가용성을 낮춥니다. 기본 업무 커밋 정책은 별도 HA 비교로 결정합니다.
- 최초 요청의 응답만 잃어도 같은 키 재주장은 매번 WAL UPDATE를 동기 확정합니다. 요청 원장을 읽기만 하고 204를 되돌려주지 않습니다.
- 권한 원장은 접근의 근거이며 USER 캐시는 후보 필터입니다. 최신 Primary에서 원장으로 확인한 최종 인가가 필요합니다. 과거 RAG 저장 결과·기존 진행 요청은 이번 범위가 아닙니다.
- 지정 동기와 quorum 비교는 동일 바이너리·strict·node_count=1·전체 on을 유지합니다. 공급사 공식 지원과 탐색 시험을 구분하며 공개 클러스터는 변경하지 않습니다.
