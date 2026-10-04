# Worker 장애 시험 코드 로컬 회귀 로그

- 실행일: 2026-10-04 KST. 명령별 원시 출력에 공통 시작·종료 타임스탬프를 남기지 않아 분 단위 전체 실행 구간은 확정하지 않음
- 위치: 로컬 격리 작업 브랜치; GCP 클러스터에는 로컬 단위 시험을 실행하지 않음
- 목적: 생성 문서의 형식·순서, 최종 상태 판정, lease 복구 기존 코드 회귀를 확인
- 비밀정보·로컬 절대경로·호스트 식별자는 이 로그에 기록하지 않음

| 명령·방법 | 관측 결과 | 판정 |
| --- | --- | --- |
| `./backend/gradlew -p backend bootJar --no-daemon` | `BUILD SUCCESSFUL`, 시험 Worker JAR SHA-256 `24ecf7767c80c99eedf455fb4f1aac3f5a6ec726f4e0a89c0cdb10f5937f0cd9` | 통과 |
| `python3 -m unittest discover -s scripts/opensql -p 'test_*.py'` | 최종 104건 실행, 실패 0 | 통과 |
| Gradle의 `EmbeddingJobLeaseRecoveryServiceTest`, `WorkerLeaseRecoverySchedulerTest`, `WorkerIndexingPipelineTest` | 6 + 4 + 4 = 14건, 실패 0 | 통과 |
| `bash -n scripts/opensql/export_worker_recovery_counts.sh scripts/opensql/ha_worker_primary_fault_gate.sh` | 문법 오류 0 | 통과 |
| 잘못된 위치에서 `python3 -m unittest scripts/opensql/test_*.py` | Python import 경로 오류 2건; 작업 디렉터리를 `scripts/opensql`로 바꿔 6건 통과, 이후 정식 `discover` 103건 통과 | 첫 호출은 시험 실패로 보존; 코드 결함 아님 |

GCP 실측 결과는 이 로컬 회귀와 별도의 각 실행 로그에 기록한다. 전체 Java 테스트 전체 스위트는 실행하지 않았으며, 위 14건만 대상화했다.
