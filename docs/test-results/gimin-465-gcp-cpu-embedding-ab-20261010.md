# GCP 앱 A/B의 CPU BGE-M3 내부 연결 검증 (2026-10-10)

- 이슈: #465
- 기준 코드: 수정 이미지의 소스 커밋 `6fd586da24dccb4632c3c548a6cd2d83a96766a3` (#462)
- 판정: **CPU 이미지 교체·A/B 내부 연결 PASS; 앱 Java 검색 경로 E2E는 미검증**

## 목적·관문

기존 CPU VM의 임시 의존성 덮어쓰기 이미지를 저장소 설정만으로 빌드된 이미지로 교체한다. 앱 A/B에 내부 URL을 차례로 적용하되, 각 앱의 health 200, 파일 권한 0600, Worker 비활성을 확인하기 전에는 다음 앱으로 진행하지 않는다. 비밀값·내부 주소·프로젝트 ID는 어느 증거 파일에도 기록하지 않는다.

## 결과

| 실행 ID | 실행 위치·방법 | 주요 숫자·사건 | 판정 |
| --- | --- | --- | --- |
| `embed465-cpu-switch-r1` | CPU VM에서 기존 컨테이너를 중지된 백업으로 남기고 수정 이미지를 동일 자원 설정·모델 캐시·8000 포트로 실행 | 04:35:50–04:36:01 KST, readiness 5번째 확인에서 성공 | PASS. 실패 시 기존 컨테이너를 되돌리는 스크립트였으나 이번에는 롤백 실행 없음. [증거](evidence/issue-465/embed465-cpu-switch-r1.md) |
| `embed465-a-direct-r1` | 앱 A VM → CPU VM 내부 주소, readiness→단일→배치 HTTP | readiness 200/12.85ms; 단일 200/1024차원/605.02ms; 배치 200/2건/437.65ms | PASS. JVM을 거치지 않은 네트워크·모델 직접 검증. [증거](evidence/issue-465/embed465-a-direct-r1.md) |
| `embed465-b-direct-r1` | 앱 B VM → 같은 CPU VM | readiness 200/10.02ms; 단일 200/1024차원/294.59ms; 배치 200/2건/310.40ms | PASS. [증거](evidence/issue-465/embed465-b-direct-r1.md) |
| `app465-config-a-r1` | 앱 A의 보호된 환경파일에 내부 URL 한 줄 추가 후 A만 재시작 | 04:38:43–04:39:34 KST, health 24번째 확인에서 200, URL 프로세스 반영, Worker=false, 파일 0600 | PASS. [증거](evidence/issue-465/app465-config-a-r1.md) |
| `app465-config-b-r1` | A 복구 후 앱 B에 동일 절차 | 04:39:49–04:40:40 KST, health 24번째 확인에서 200, URL 프로세스 반영, Worker=false, 파일 0600 | PASS. [증거](evidence/issue-465/app465-config-b-r1.md) |

## 전후 상태와 해석

전: A/B 앱은 같은 JAR을 실행했지만 `EMBEDDING_SERVER_URL`이 설정되지 않아 기본 localhost 주소를 쓸 상태였다. CPU VM은 임시 패키지 덮어쓰기 이미지였다.

후: CPU VM은 #462의 저장소 기반 이미지를 실행한다. A/B의 서비스 환경에는 같은 CPU VM의 내부 URL이 반영됐고 health 2/2가 회복했다. 기존 CPU 컨테이너는 중지된 백업으로 남아 있으며, Worker는 A/B 모두 계속 꺼져 있다. DB Job·GCS 객체는 이 실행에서 변경하지 않았다.

## 한계와 다음 관문

- 앱 VM에서 보낸 직접 HTTP 요청과 Java `EmbeddingClient` 호출은 다르다. 로그인한 사용자의 검색·Worker 경로는 아직 확인하지 않았다.
- #462의 CPU 초기 단일 요청은 16,461.51ms로 앱 조회 기본 read timeout 5초를 넘었다. 이번의 0.3–0.6초는 모델이 준비된 뒤의 소수 샘플이며 cold start 또는 부하 상황의 p95가 아니다.
- #463의 즉시 claim 가능 PENDING 6건·만료 PROCESSING 2건이 남아 있으므로 Worker를 전체 큐에 대해 활성화하지 않는다. 다음 단계에서 처리 범위를 별도로 통제한다.
- CPU VM에 남긴 이전 컨테이너는 롤백을 위해 보존했다. CPU VM의 자동 중지 정책은 이 시험으로 바꾸지 않았다.
