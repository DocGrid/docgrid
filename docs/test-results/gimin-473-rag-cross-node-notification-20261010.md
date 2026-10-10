# 다중 백엔드 RAG 개인 알림 전달과 유휴 연결 유지

- 이슈: [#473](https://github.com/DocGrid/docgrid/issues/473), 발견 근거 [#472](https://github.com/DocGrid/docgrid/pull/472)
- 목적: 앱 A/B가 각자 보유한 STOMP SimpleBroker 때문에 다른 앱에서 완료한 RAG 알림이 사용자 소켓에 닿지 않는 경계를 메우고, 프런트가 협상한 heartbeat를 실제로 보낸다.
- 실행일: 2026-10-10 KST
- 결론: **Java 19/19·프런트 49/49 통과, GCP 앱 A/B readiness 2/2, 공개 WSS MESSAGE 1건, Worker A→소켓 B 교차 전달 1건. RAG 답변 생성은 Ollama 미배포로 여전히 실패.**

## 변경 전후 경로

```text
이전: Worker A ── convertAndSendToUser ──▶ A의 로컬 broker
                                      B에 연결된 구독자에게 전달 경로 없음

수정: Worker A ── 완료 후 query ID만 Redis Pub/Sub 발행 ──▶ 앱 B
          │                                             ├─ DB에서 실제 소유자 이메일 조회
          └─ A 로컬 세션에 직접 알림                      └─ B 로컬 broker의 그 사용자 queue에 알림

프런트는 MESSAGE를 최종 답변으로 믿지 않고 GET /search/{queryId}로 다시 조회한다.
Pub/Sub 단절 중 신호는 쌓이지 않으며 기존 주기 REST 폴링이 수렴 안전망이다.
```

## 실행별 증거

| 실행 ID | 목적·위치·명령 또는 방법 | 관측 | 판정 |
| --- | --- | --- | --- |
| `rag473-java-r1` | 로컬 Gradle 대상 테스트·bootJar | 19/19 통과, JAR 해시 고정 | [로그](evidence/issue-473/rag473-java-r1.md) |
| `rag473-frontend-r1` | 로컬 `eslint`, Vinext build·`npm test` | lint 0·빌드 성공·49/49 통과; 독립 `tsc`는 기존 설정·권한 오류 | [로그](evidence/issue-473/rag473-frontend-r1.md) |
| `rag473-deploy-r1` | 앱 A 다음 B 순차 배포·readiness | 새 JAR 동일, Worker off, readiness 200 2/2 | [로그](evidence/issue-473/rag473-deploy-r1.md) |
| `rag473-public-ws-r1` | 공개 인증 STOMP·검색 | query #80 RAG FAILED지만 `MESSAGE=1` | [로그](evidence/issue-473/rag473-public-ws-r1.md) |
| `rag473-crossnode-r1` | B 소켓·A HTTP, Worker 노드 대조 | query #81 Worker B·소켓 B, `MESSAGE=1` | 로컬 표본. [로그](evidence/issue-473/rag473-crossnode-r1.md) |
| `rag473-crossnode-r2` | B 소켓·A HTTP, Worker 노드 대조 | query #82 Worker **A**·소켓 **B**, `MESSAGE=1` | 교차 전달 통과. [로그](evidence/issue-473/rag473-crossnode-r2.md) |
| `rag473-crossnode-r3~r5` | A 소켓·B HTTP 세 번 반복 | query #83~85 모두 Worker A·소켓 A, 각 `MESSAGE=1` | 반대 방향 교차 미관측. [개별 로그](evidence/issue-473/rag473-crossnode-r3.md) · [r4](evidence/issue-473/rag473-crossnode-r4.md) · [r5](evidence/issue-473/rag473-crossnode-r5.md) |

## 해석·한계·원복

- Redis에는 발행자 UUID와 query ID만 전송한다. 수신 앱이 DB에서 query 소유자 이메일을 조회하므로 payload로 임의 수신자를 지정할 수 없다. 자기 Redis echo는 무시한다.
- 로컬 단위 시험은 발행·수신 객체를 분리했고, GCP 실측은 A Worker→B 소켓을 확인했다. B Worker→A 소켓은 세 번의 시도에서 B가 Job을 claim하지 않아 미관측이다.
- Java Worker Javadoc에는 단일 인스턴스 가정의 startup stale-claim 회수가 남아 있다. 이 변경은 **알림 배달**만 다루며 RAG Job의 완전한 다중 인스턴스 lease 안전성을 새로 증명하지 않는다.
- RAG 답변 생성은 `RAG-001`이며, 두 앱에 `OLLAMA_SERVER_URL`이 없어 기본 localhost를 사용하고 11434 포트가 닫혀 있다. 최근 Ollama 호출 실패 로그도 A 5건·B 2건이었다. 이 Fix를 답변 생성 복구로 주장하지 않는다.
- 공개 WSS 재시험은 새 백엔드 JAR에서 이뤄졌다. 프런트 heartbeat 코드는 로컬 빌드·테스트를 통과했지만 Vercel 새 배포의 브라우저 90초 유지 여부는 아직 별도 확인이 필요하다.
- IAP 테스트 터널 2개는 종료했고 로컬 포트가 닫힌 것을 확인했다. 앱 A/B는 새 JAR·Worker 비활성 상태를 유지한다. DB·Redis VM 설정은 변경하지 않았다. 중복 비밀이 들어 있는 배포 백업 설정 파일과 임시 키는 최종 확인 후 제거해야 한다.
