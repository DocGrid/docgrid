# 이슈 #376 로컬 시험 실행 목록

각 목적·재실행을 별도 파일로 보존한다. 시각은 KST다. 명령의 포트는 로컬 격리 시험 포트이며, 시험용 JWT 문자열도 값 대신 `[TEST_ONLY_VALUE]`로 기록한다. 공개하지 않은 Gradle/JUnit 원문에는 로컬 호스트명 등 환경 식별자가 포함될 수 있어 이 한국어 요약만 저장한다.

| 실행 ID | 목적 | 결과 | 로그 |
| --- | --- | --- | --- |
| header-01 | 내부 헤더 관문 첫 실행 | Gradle 캐시 권한 거부, 시험 본문 0건 | [header-01](header-01.md) |
| header-02 | 동일 관문 재실행 | 1/1 통과 | [header-02](header-02.md) |
| unit-01 | 초기 단위 회귀 | 21/21 통과 | [unit-01](unit-01.md) |
| integration-01 | 실제 역할 회수·헤더 | 3건 중 1건 실패 | [integration-01](integration-01.md) |
| integration-02 | 시험 입력 보정 후 재실행 | 3/3 통과 | [integration-02](integration-02.md) |
| redis-01 | Redis 무효화 실패 포함 | 4/4 통과 | [redis-01](redis-01.md) |
| regression-01 | 기존 WebSocket 회귀 | 12/12 통과 | [regression-01](regression-01.md) |
| combined-01 | 세션 ID 보완 전 관련 10개 클래스 | 48/48 통과 | [combined-01](combined-01.md) |
| full-01 | 기본 전체 시험 첫 실행 | 1,251건 중 24건 실패 | [full-01](full-01.md) |
| full-02 | 시험용 JWT 설정 후 전체 재실행 | 1,270/1,270 통과 | [full-02](full-02.md) |
| combined-02 | 세션 ID 경합 수정 후 첫 회귀 | 잘못 지정한 클래스 경로로 9개 클래스·45/45 통과 | [combined-02](combined-02.md) |
| combined-03 | 누락 클래스 포함 회귀 재실행 | 10개 클래스·49/49 통과 | [combined-03](combined-03.md) |
| full-03 | 세션 ID 경합 수정 후 전체 재실행 | 210개 suite·1,271/1,271 통과 | [full-03](full-03.md) |
