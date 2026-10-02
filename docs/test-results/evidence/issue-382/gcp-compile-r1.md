# GCP A/B 격리 checkout 컴파일 — `ab382-compile-r1`

| 항목 | 기록 |
| --- | --- |
| 목적 | 실제 두 백엔드 VM에서 동일한 앱·시험 코드가 컴파일되는지 확인 |
| 위치·시각 | GCP 백엔드 A/B, 2026-10-02 KST |
| 기준 | 양쪽 checkout의 커밋 `9cd1c10b8d1d14fdc70184ee08e974905e5c8880`; 기존 미커밋 checkout과 분리 |
| 명령 | 각 VM의 격리 checkout에서 `./backend/gradlew -p backend compileTestJava --offline --no-daemon` |
| 성공 조건 | A/B 명령 각각 종료 코드 0 |
| 관측 결과 | A **0**, B **0**. 처음에는 잘못 추정한 `.class` 위치를 검사해 파일을 찾지 못했으나, 실제 Gradle 태스크를 직접 재실행해 양쪽 종료 코드 0을 확인 |
| 해석 | 컴파일 성공은 실행·DB 연결의 증거가 아니다. 그 경로는 별도의 A→B·B→A run에서 검증했다 |

Gradle 원시 출력은 내부 경로가 들어갈 수 있어 공개 로그로 캡처하지 않았다. 출력 억제 후 종료 코드만 기록했으며, 실패한 클래스 파일 위치 추정도 성공으로 바꾸어 서술하지 않았다.
