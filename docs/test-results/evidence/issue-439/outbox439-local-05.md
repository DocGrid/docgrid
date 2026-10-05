# 기본 전체 테스트 첫 재실행: DB 포트 불일치

- 실행 ID: `outbox439-local-05`
- 실행일: 2026-10-06 KST. 중단 시각은 별도 보존하지 못했다.
- 목적: 클라우드 전용 태그를 제외한 기본 `gradle test` 회귀 확인
- 위치: 로컬 Java 17. GCP DB 터널은 이미 종료된 상태
- 성공 기준: 기본 테스트 전체 통과, 격리 OpenSQL 태그는 미실행

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| `./backend/gradlew -p backend test --rerun-tasks --console=plain --no-daemon` | 다수 Spring Context에서 PostgreSQL `ConnectException` 발생, 실행을 중단해 종료 코드 **130** | 테스트 코드 판정이 아니라 로컬 DB 접속 전제 오류 |
| `docker port`와 테스트 프로필 설정 대조 | 현재 개발용 PostgreSQL은 로컬 포트 **55433**, `application-test.yml` 기본값은 **55432** | 실행 전 격리 시험 DB를 55432에 준비하지 않은 것이 원인 |
| 사후 확인 | GCP DB 접속·변경 **0건** | 클라우드 Dispatcher 테스트와 분리 |

이 실행의 테스트 성공/실패 총계는 중단돼 확정하지 않았다. 개발 DB를 시험 DB로 재사용하지 않고 55432에 일회용 PostgreSQL을 준비해 새 실행으로 검증했다. 원본 콘솔의 로컬 경로·호스트 식별자는 커밋하지 않았다.
