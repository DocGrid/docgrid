# 최신 develop 병합 후 HA probe Java 계약

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-idem-java-20261010-03`, 2026-10-10 11:37 KST |
| 환경·리비전 | 로컬 작업 브랜치, `origin/develop`의 `105d6d6`을 충돌 없이 병합한 `9b92eca` |
| 목적·명령 | 최신 변경과 새·기존 HA probe 계약의 호환성; `./backend/gradlew -p backend test --tests '*HaProbeIdempotentWrite*' --tests '*HaProbeWrite*' --no-daemon --console=plain` |
| 성공 기준·관측 | 선택 시험 전부 통과; JUnit **19건 통과·실패 0·건너뜀 0**, 종료 코드 **0** |
| 한계 | 전체 백엔드 테스트는 이 실행에 포함하지 않음. 앞선 서비스 DB 연결 실패 기록 유지 |
