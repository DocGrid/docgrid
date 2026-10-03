# 로컬 코드·증거 도구 검증 이력

- 목적: 장애 시험 전에 시험 프로필 게이트·안전 로그·실패 단계·원장 조인을 확인하고, 장애 실행 후 최종 코드를 재검증.
- 위치: 로컬 개발 환경의 격리된 `test/418` 작업 트리. 아래 시험은 **GCP 장애 시험이 아니다**.
- 기준: Java 관련 suite 전부 통과, Python sanitizer/analyzer 합성 시험 통과, JS 문법 통과. 실패한 준비 시도는 최종 결과와 합치지 않는다.
- 시간대: UTC. 실행 시각을 별도로 수집하지 못한 초기 반복은 임의 시각을 적지 않았다.

| 실행 ID·시각 | 목적·명령/위치 | 실제 결과 요약 | 판정·해석 |
| --- | --- | --- | --- |
| `ha418-local-01` · 시각 미수집 | Java 4개 관련 suite, 로컬 `./backend/gradlew -p backend test --tests …` | **8건 중 1건 실패** | 테스트가 일반 단어 `password`를 모든 Spring 출력에서 금지해, 개발용 생성 암호 안내와 충돌. 예외 원문·개인정보 출력 여부를 판정하는 정확한 합성 문자열 검사로 좁힘. 원시 콘솔을 증거로 보존하지 않음 |
| `ha418-local-02` · 시각 미수집 | 위 동일 suite 재실행 | **8/8 통과** | 테스트 단언 수정 후 통과 |
| `ha418-local-03` · 시각 미수집 | SQLSTATE 중첩 예외·기본 프로필 비노출 추가 후 관련 suite | **10/10 통과** | 테스트용 진단 코드의 초기 기준 |
| `ha418-local-04` · 시각 미수집 | 새 ID 형식 적용 뒤 같은 Gradle 명령, sandbox | **시험 시작 전 실패·실행 0건** | Gradle 래퍼가 작업 트리 밖 캐시 잠금 파일에 접근하지 못한 환경 권한 문제. 코드 실패로 계산하지 않음 |
| `ha418-local-05` · 시각 미수집 | 같은 명령을 승인된 실행 환경에서 재실행 | **10건 중 1건 실패** | 진단 ID를 합성 `haNNN…` 형식으로 제한했지만 테스트 fixture는 이전 `run-1` 사용. 테스트 ID 교정 |
| `ha418-local-06` · 시각 미수집 | ID 교정 뒤 관련 suite 재실행 | **10/10 통과** | ID 화이트리스트·MDC 정리 확인 |
| `ha418-local-07` · 시각 미수집 | Security 테스트 포함 6개 suite | **11/11 통과** | 관리자 권한 없는 요청의 probe 진입 차단 확인 |
| `ha418-local-08` · 2026-10-03 19:18:38 UTC | 최종 관련 6개 suite, 409 계약 추가 후 `./backend/gradlew -p backend test --tests com.opensource.docgrid.global.diagnostics.HaProbeDiagnosticContextTest --tests com.opensource.docgrid.global.diagnostics.HaProbeDiagnosticFilterTest --tests com.opensource.docgrid.domain.failover.controller.HaProbeWriteControllerTest --tests com.opensource.docgrid.domain.failover.controller.HaProbeWriteDisabledTest --tests com.opensource.docgrid.domain.failover.controller.HaProbeWriteSecurityTest --tests com.opensource.docgrid.domain.failover.service.command.HaProbeWriteServiceTest --console=plain` | **12/12 통과**, 실패·오류 0 | 최종 Java 범위 통과. 전체 Gradle suite는 미실행 |
| `ha418-py-01` · 시각 미수집 | 저널 비식별기 단위 시험 | **2/2 통과** | 원문·호스트·비밀번호 필드가 결과로 복사되지 않음 |
| `ha418-py-02` · 시각 미수집 | 요청 ID 대조기 단위 시험 | **2/2 통과** | 500 조인·다른 run의 이벤트 거부 |
| `ha418-py-03` · 시각 미수집 | 통합 `python3 -m unittest discover -s scripts/opensql -p 'test_*ha_probe*.py' -v` | **4/4 통과** | 두 목적을 함께 재실행 |
| `ha418-py-04` · 2026-10-03 19:21:31~19:21:32 UTC | 동일 Python 명령 최종 재실행 | **4/4 통과** | 최종 소스 상태에서 재확인 |
| `ha418-js-01` · 시각 미수집 | `node --check scripts/opensql/ha_probe_load.js` | 종료 코드 0 | k6 JS 정적 문법 확인. 실제 k6는 별도 GCP 3개 실행으로 검증 |

원시 Java 테스트 XML·전체 콘솔은 개발 환경 호스트 이름과 Spring의 임시 개발용 암호 안내가 포함될 수 있어 저장소 증거로 복사하지 않았다. 여기의 숫자와 실패 이유는 명령 반환값·안전한 테스트 수치만 발췌한 **사후 요약**이다. GCP k6 실시간 기록은 각 실행의 별도 `실행-기록.txt`와 정제 이벤트에 보존했다.
