# fix382-local-unit-r1 — 단위 시험 준비 실패

- 시각: 2026-10-02 KST, 시작 초 단위는 기록하지 못함.
- 위치/기준: 로컬 격리 checkout, `fix/382` 작업 트리.
- 목적·성공 기준: 대시보드 관련 Java 단위 테스트 19건 실행·통과.
- 실행: `./backend/gradlew -p backend test --tests <관련 4개 클래스> --console=plain --no-daemon`.
- 관측: Gradle wrapper 캐시의 `.zip.lck`를 열 때 `Operation not permitted`; 실행된 테스트 **0건**.
- 판정: **환경 실패**. 코드 실패로 세지 않았다. 같은 명령을 필요한 파일 접근 권한으로 별도 재실행했다.
- 비밀/정리: 출력에 비밀은 기록하지 않았고, 시험 자원 생성 없음.
