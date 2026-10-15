# `hwp-hwpx-package-20261015-02`

- 시각: 2026-10-15 KST. 정확한 시작·종료 시각은 별도 수집하지 않았다.
- 위치·리비전: 격리 로컬 작업 트리의 미커밋 `feature/505`, `origin/develop` 기준 `31d5ac459b3da861bc957a59eb8ba433d85507c5`.
- 목적: 실행 JAR에 Apache-2.0 라이선스 사본과 새 라이브러리의 출처가 포함되는지 확인.
- 명령: `./backend/gradlew -p backend build -x test --console plain`; `jar tf backend/build/libs/docgrid-0.0.1-SNAPSHOT.jar`; 루트·JAR `META-INF/LICENSE` 각각 SHA-256 확인.
- 합격 기준: 패키징 성공, `hwplib`·`hwpxlib`와 `META-INF/LICENSE`·`META-INF/THIRD-PARTY-HANCOM.txt` 존재, 라이선스 바이트 일치.
- 관측: 빌드 **성공**. 실행 JAR에서 네 항목 모두 확인. 루트와 JAR의 라이선스 SHA-256은 모두 `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`으로 일치했다.
- 판정: **통과**. `-x test` 패키징 검사이며, 전체 전이 의존성의 라이선스 감사나 대회 공식 검증 판정은 아니다. DB·GCS·VM 자원 변경은 없었다.
