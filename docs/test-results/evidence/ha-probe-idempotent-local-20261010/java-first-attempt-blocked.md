# Java 계약 테스트 첫 시도 — 실행 전 차단

| 항목 | 기록 |
| --- | --- |
| 실행 ID | `local-ha-idem-java-20261010-attempt-01` |
| 시각 | 2026-10-10 04:43 KST |
| 위치·리비전 | 로컬 격리 작업 공간, `db4c220` + 미커밋 변경 |
| 목적·명령 | HA probe Java 계약 검증; `./backend/gradlew -p backend test --tests '*HaProbeIdempotentWrite*' --tests '*HaProbeWrite*' --no-daemon` |
| 관측 | 기존 Gradle 캐시 잠금 파일 쓰기가 샌드박스에서 거부됨. **테스트 시작 0건**, 종료 코드 1 |
| 해석·복구 | 코드 테스트의 실패로 판정하지 않음. 허용된 Gradle 캐시에서 같은 선택 테스트를 별도 재실행해 통과 |
