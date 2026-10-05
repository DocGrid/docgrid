# 기본 테스트 분리·전용 태스크 안전 가드

- 실행 ID: `outbox439-isolated-guard-01`
- 실행일: 2026-10-06 KST, 전용 DB 재생성 전
- 목적: 격리 DB 환경 변수가 없을 때 외부 DB·Flyway·Dispatcher에 접속하지 않도록 빌드 경계에서 중단
- 위치: 로컬 Gradle 설정 평가만 수행, GCP DB 접속 없음
- 성공 기준: 기본 `test`는 `opensql-outbox-isolated` 태그를 제외하고, 전용 `openSqlOutboxIsolatedTest`는 잘못된 DB 이름을 JVM 시작 전에 거부

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 권한 제한된 로컬 셸에서 `./backend/gradlew -p backend openSqlOutboxIsolatedTest --console=plain --no-daemon` | Gradle wrapper의 로컬 lock 파일 접근이 거부돼 태스크 시작 전 실패 | DB나 테스트 결과로 분류하지 않음. 로컬 실행 권한 문제 |
| 승인된 Gradle 실행 환경에서 같은 명령 재실행, `OUTBOX_ISOLATED_DB_NAME` 미설정 | 컴파일은 UP-TO-DATE, 전용 태스크 `doFirst`에서 **“전용 Outbox 격리 DB 이름이 필요합니다”**로 예상대로 거부. 테스트 JVM·Flyway 실행 **0건** | 원래 DB로의 우발적 외부 쓰기를 시작 전에 차단 |
| `backend/build.gradle`의 기본 `test` 설정 확인 | `opensql-outbox-isolated` 태그를 기본 태스크 제외 목록에 추가 | 일반 개발·CI 테스트에 전용 클라우드 DB 요구가 섞이지 않음. 전체 기본 테스트 재실행은 이 기록에서 하지 않음 |

이 검증의 비영(非零) 종료 코드는 **안전 가드 통과**다. 첫 시도는 샌드박스 파일 권한 오류로 원인·판정에서 분리했다. 원본 콘솔의 로컬 경로와 호스트 정보는 기록하지 않았다.
