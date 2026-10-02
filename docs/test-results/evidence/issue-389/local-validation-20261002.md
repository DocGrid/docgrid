# 이슈 #389 로컬 코드 검증 로그

시간대는 UTC다. 명령 출력 원문에는 개발자 로컬 경로 등이 포함될 수 있어 그대로 공개하지 않고, 실행 직후 JUnit XML에서 건수와 예외 **종류만** 추출했다. 이 파일은 원본 출력이 아니라 비식별 요약이다.

| 실행 시각(UTC) | 목적·실행 위치 | 실행 명령 또는 방법 | 결과 요약 | 해석 |
| --- | --- | --- | --- | --- |
| 2026-10-02 09:46 | 비활성 경로 웹 테스트·로컬 | `./gradlew test --tests '*HaProbeWriteControllerTest' --tests '*HaProbeWriteDisabledTest'` | 3건 중 1건 실패. 미등록 경로에 HTTP 404를 기대했지만 프로젝트 전역 예외 처리 결과는 500이었다. | 컨트롤러 활성 여부를 HTTP 상태로 추정한 **테스트 판정 오류**였다. 운영 코드의 쓰기 실패로 해석하지 않았다. |
| 2026-10-02 09:50경 | 판정 수정 후 웹 테스트·로컬 | 같은 두 테스트 클래스 재실행 | 3건 통과, 실패 0건 | 요청 상태 대신 Spring의 실제 handler mapping에 probe 경로가 없는지 확인하도록 바꿨다. 정확한 재실행 시각은 초기 출력에서 별도로 보존하지 못했다. |
| 2026-10-02 11:06:24~11:06:34 | HTTP·JPA 경계 회귀·로컬 | `./gradlew test --tests '*HaProbeWriteControllerTest' --tests '*HaProbeWriteDisabledTest' --tests '*HaProbeWriteServiceTest'` | 4건 통과, 실패 0건 | 201·입력 거부·기본 프로필 미등록·JPA 쓰기 호출을 확인했다. |
| 2026-10-02 11:09:16~11:09:18 | 추가 보안 경계·로컬 | `./gradlew test --tests '*HaProbeWrite*Test'` | 5 suite, 6건 통과, 실패 0건 | `ha-probe` 프로필만 있고 활성화 플래그가 꺼진 경우 경로 미등록, 비ADMIN 인증 사용자의 403을 추가 검증했다. |
| 2026-10-02 11:06:08~11:06:09 | 원장·대조 회귀·로컬 | `python3 -m unittest discover -s scripts/opensql -p 'test_ha_evidence.py' -v` | 11건 통과, 실패 0건 | 대량 import, 비허용 필드 거부, DB 중복 탐지, k6 드롭과 DB 내구성의 분리 판정까지 확인했다. |
| 2026-10-02 11:09:35~11:10:27 | 전체 Backend 회귀·로컬 | `JWT_SECRET=<시험용 값> DB_PORT=55433 ./gradlew test --console=plain` | 219 suite, 1,238건 중 실패 124건·건너뜀 2건. 이번 HA probe 관련 6건은 모두 통과. | 로컬 PostgreSQL 시험 포트 연결이 되지 않아 `ConnectException` 31건에서 Flyway/Context 실패가 파생됐다. 전체 통과로 주장하지 않는다. GCP 실측과는 별도 환경이다. |
| 2026-10-02 11:10 이후 | 로그 무결성·로컬 | `ha_evidence.py verify` 10개 실행, `gzip -t`·비압축 원본 `cmp` 40개 파일 | 원장 10/10, 압축 복원 40/40 통과 | PR에는 압축 증거를 보관하고, 원본 JSONL·CSV는 시험 작업 공간과 GCP 실행 VM에 유지한다. |

전체 회귀 실패를 새 코드 탓으로도, 전체 품질 통과로도 해석하지 않는다. 로컬 PostgreSQL 포트가 닫힌 상태(`nc -z 127.0.0.1 55433` 실패)에서 실행된 결과다. 실제 GCP HTTP→OpenProxy→OpenSQL 경로의 성공 여부는 별도 [실측 결과](../../gimin-389-opensql-ha-http-write-baseline-20261002.md)로 판정한다.

원장 코드 최종 상태는 [별도 재실행 기록](local-python-rerun-20261002-1115.md)에서 **11/11**로 다시 확인했다. 반복 실행의 로그와 기존 실행 로그를 합치거나 덮어쓰지 않았다.
