# GCP 내부 시험 인프라·정리 체크 (2026-10-02 KST)

이 파일은 비밀·프로젝트·내부 주소를 저장하지 않은 **명령 출력의 정제 요약**이다. WebSocket 원본 이벤트·k6 표본·A/B 발행 행은 각 run 디렉터리에 별도로 보존했다.

| 목적/실행 위치 | 실행한 명령·방법 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| VM 종류 / GCP 인스턴스 조회 | `gcloud compute instances list --format=json`에서 이름 대신 역할·상태·머신 타입만 추출 | 백엔드 A/B와 부하 VM 각각 `e2-standard-2`, Redis VM `e2-small`; 4대 모두 `RUNNING` | 승인된 시험 인프라는 삭제·중지하지 않았다. |
| OS / 각 VM | `/etc/os-release`, `uname -m` | A/B/Redis/부하 VM 모두 Rocky Linux 9.8, `x86_64` | OpenSQL **DB 노드의 9.7**과 시험 앱 VM 버전은 별개다. |
| Redis / Redis VM | `systemctl is-active redis`; 비밀번호 파일 모드 0600 확인 후 인증된 `redis-cli PING` | `active`, `PONG`, 원본 비밀번호 파일 0600 | Redis 서비스는 시험 종료 후에도 실행·인증 가능하다. |
| A/B → 공용 Redis / Redis VM | 인증된 `redis-cli CLIENT LIST`를 메모리에서만 읽고 내부 주소를 A/B 역할로 변환 | A발 클라이언트 1개, B발 클라이언트 1개 | 양쪽이 같은 Redis에 실제 접속했다. 주소는 출력·저장하지 않았다. |
| 회수 후 Redis / A VM | `GET auth:roles:epoch:<시험사용자>`와 `EXISTS auth:roles:<시험사용자>` | epoch `1`, 캐시 키 존재 `0` | API 회수 뒤 세대 증가·역할 캐시 제거를 확인했다. 키·사용자 ID는 기록하지 않았다. |
| 초기 LB / GCP backend health | `gcloud compute backend-services get-health …`에서 A/B 상태만 추출 | 시험 중 A/B 모두 `HEALTHY` | 두 VM이 실제 LB 건강 검사에 들어왔다. |
| A 정상 종료 후 LB / GCP backend health | 동일한 읽기 전용 상태 조회 | A `UNHEALTHY`, B `HEALTHY` | 새 연결은 살아 있는 B로 갈 수 있는 상태였다. |
| 모든 fixture 종료 후 LB / GCP backend health | 동일한 읽기 전용 상태 조회 | A/B 모두 `UNHEALTHY` | 현재 VM은 켜져 있어도 앱 프로세스는 내려가 있다. **즉시 서비스 가능한 LB라고 주장하지 않는다.** |
| 시계 / A·B·부하 VM | `chronyc tracking`의 `System time`, `Leap status`만 조회 | A +5.638µs, B +0.048µs, 부하 −16.576µs; 모두 `Normal` | 발행→수신 지연 해석의 후행 확인이다. 시험 중 매 순간의 오차 상한은 아니다. |
| 시험 사용자 / A/B fixture | `finished`와 `launch-status.txt` 조회 | run03·run04 모두 양쪽에서 사용자 2개씩 정리, Gradle 종료 코드 0 | 각 실행의 임시 DB 사용자·역할 연결을 제거했다. |
| 시험 비밀 / A/B·부하·로컬 | 실행 종료 후 정확한 파일명을 확인하고 임시 DB/JWT/Redis 암호 복사본·시험 토큰 파일만 삭제 | A/B 임시 비밀 복사본 제거; 부하 VM 토큰 5개 제거; 로컬 0600 임시 파일 3개와 디렉터리 제거 | Redis VM의 **서비스용 원본** 비밀번호 파일은 서비스 유지를 위해 0600으로 남겼다. |
| 임시 네트워크 예외 / GCP 방화벽 | 단기 JWT 전송용 SSH 허용 규칙의 단일 대상·포트를 조회한 뒤 해당 규칙만 삭제 | 임시 SSH 허용 규칙 삭제 완료 | 기존 IAP/앱/LB/Redis 방화벽은 유지한다. |
| 공개 전 검사 / 로컬 evidence 폴더 | `rg -l`로 토큰·내부 주소·프로젝트·시험 사용자 이메일 패턴 검사 | 매치 파일 0개 | 패턴 검사는 보조 안전장치이며, 원시 Gradle/Spring 로그는 처음부터 수집·공개하지 않았다. |

처음 시도한 A/B 세션 계측은 `/actuator/prometheus` HTTP 401로 47번 실패했다. 해당 탐침 실패는 [별도 회차 요약](runs/ab380-lb-n50-r1/sessions/summary.json)에 남겼고, 최종 판정에는 A/B fixture의 `StompSessionRegistry` 값만 사용했다. STOMP 신규 권한 탐침도 CONNECT만 검사한 첫 회차는 무효 판정으로 두고 [SUBSCRIBE 재시험](runs/ab380-revoked-subscribe-r2.json)을 별도 실행했다.
