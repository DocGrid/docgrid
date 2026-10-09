# 백엔드 CORS·권한 회귀 단위 시험

- 실행 ID: `vercel-backend-java-unit-20261010-02`
- 실행 시각: 2026-10-10 KST, 배포 후 로컬 재실행.
- 위치·대상: 개발 컴퓨터, 변경 브랜치의 Gradle/JUnit.
- 목적·통과 기준: Vercel 정확한 Origin이 HTTP CORS와 WebSocket 허용 목록에 함께 존재하고, 배포 JAR에 포함된 권한 영역 회귀가 없어야 한다.
- 명령: `./gradlew test --tests 'com.opensource.docgrid.global.config.CorsConfigTest' --tests 'com.opensource.docgrid.domain.permission.service.query.PermissionQueryServiceTest' --tests 'com.opensource.docgrid.domain.permission.service.command.CollectionPermissionCommandServiceTest' --console=plain`
- 관측: `BUILD SUCCESSFUL`. `CorsConfigTest` 1건, `PermissionQueryServiceTest` 58건, `CollectionPermissionCommandServiceTest` 11건으로 합계 70건, skip·failure·error 각 0건.
- 해석: 새 Origin 규칙과 관련 권한 단위 시험이 로컬에서 통과했다. 브라우저 인증 E2E나 전체 백엔드 시험 성공을 뜻하지 않는다.
