# `hwp-hwpx-package-20261015-01`

- 시각: 2026-10-15 KST. 정확한 시작·종료 시각은 별도 수집하지 않았다.
- 위치·리비전: 격리 로컬 작업 트리의 미커밋 `feature/505`, `origin/develop` 기준 `31d5ac459b3da861bc957a59eb8ba433d85507c5`.
- 목적: 새 HWP/HWPX 라이브러리가 백엔드 실행 JAR에 포함되는지 확인.
- 명령: `./backend/gradlew -p backend build -x test --console plain` 후 `jar tf backend/build/libs/docgrid-0.0.1-SNAPSHOT.jar`로 목록 확인.
- 합격 기준: 패키징 성공, 두 라이브러리 포함, 배포 고지 확인.
- 관측: 빌드 **성공**, `hwplib-1.1.11.jar`와 `hwpxlib-1.0.9.jar`는 포함. 실행 JAR 안에서 `LICENSE`·`NOTICE` 항목은 확인되지 않았다.
- 판정: **고지 기준 미달**. 이 실행은 `-x test`여서 테스트 결과가 아니다. 다음 변경에서 루트 라이선스와 구성요소 출처를 실행 JAR에 추가했다.
