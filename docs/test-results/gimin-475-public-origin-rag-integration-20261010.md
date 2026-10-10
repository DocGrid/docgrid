# 공개 Vercel Origin·RAG 교차 노드 알림 통합 배포 검증

- 실행일: 2026-10-10 KST
- 이슈: #475
- 소스 기준: `develop` 병합 커밋 `04c9b0ceb7b9a6ec6982e3e1091abae91ae37a57` (#459 + #474)
- 배포 JAR SHA-256: `eafc09e71fd60008baf42ca3cfbd10c5af7c6e986688ec7c37d21b2ab655e7a8`
- 환경: Vercel Production → 기존 공개 게이트웨이 → 내부 LB → 앱 A/B → 공용 Redis·OpenSQL. 내부 주소·계정·토큰은 원본을 쓰기 전에 생략했다.

## 목적과 성공 기준

앞선 RAG 교차 노드 JAR은 공개 Vercel Origin 허용 변경이 아직 `develop`에 병합되기 전에 빌드됐다. Origin 없는 Node WebSocket은 통과했지만 실제 브라우저 Origin의 업그레이드는 403이었다. 공개 서비스를 즉시 이전 JAR로 되돌린 뒤, #459와 #474가 합쳐진 소스로 다시 빌드했다.

성공 기준은 (1) A/B 동일 JAR과 readiness 200, (2) 실제 Vercel Origin에서 로그인 200·WebSocket 101·STOMP CONNECTED, (3) 검색 완료 MESSAGE 1건, (4) A Worker → B 소켓 전달 1건, (5) 자동 원복 타이머와 비밀 포함 임시 백업 정리다. 생성형 답변 성공은 이번 알림 경로 시험의 성공 기준이 아니다.

## 동작 경로와 회귀 경계

```text
그림 1. 공개 요청과 개인 알림의 경로

시험 계정 브라우저·합성 클라이언트
  ├─ HTTPS 로그인·검색 ──▶ Vercel /api/backend/*
  │                         └─ 공개 게이트웨이 → 내부 LB → 앱 A/B
  └─ 실제 Vercel Origin + JWT ──▶ WSS 공개 게이트웨이
                                   └─ 내부 LB → 앱 B의 STOMP 개인 구독

앱 A의 RAG Worker ──▶ OpenSQL에 최종 상태 저장
                 ├─ query ID만 공용 Redis Pub/Sub으로 발행
                 └─ 앱 B가 DB 소유자를 검증한 뒤 B의 개인 queue에 전달
                      → 구독자는 MESSAGE를 받고 GET /search/{queryId} 재조회
```

이 그림에서 검색 결과 상태와 STOMP 알림은 별개다. 이번에는 MESSAGE가 왔지만 최종 답변 상태는 `FAILED`였다.

```text
그림 2. 두 브랜치가 분리돼 생긴 실제 회귀와 복구

첫 배포 JAR: RAG 교차 노드 수정 포함 / Vercel Origin 허용 없음
  ├─ Origin 없는 Node 접속: 통과
  └─ 실제 Vercel Origin: HTTP 403
        ↓ 즉시 이전 JAR로 A/B 원복 → 공개 STOMP CONNECTED 재확인

#459 Origin 허용 + #474 RAG 수정을 develop에 병합
        ↓ 동일 커밋에서 새 JAR·프런트 빌드
        ↓ A readiness 200 → B readiness 200
        ↓ 실제 Origin HTTP 101 + CONNECTED + MESSAGE 1건
```

Origin 없는 시험의 통과를 브라우저 성공으로 확대하지 않은 이유와 원복·재배포의 경계를 나타낸다.

```text
그림 3. 고정 노드 재시험과 실패를 분리한 판정

첫 실행: B 소켓 → 관리 포트 8081
    └─ 정적 리소스 경로 500 / query 생성 0 / 알림 0
       → 시험 배선 오류로 종료, 터널 닫음

재실행: B 소켓 → 앱 포트 8080   A 검색 → 앱 포트 8080
    ├─ A 검색 완료·RAG enqueue
    ├─ A Worker가 RAG-001 fallback 확정
    ├─ Redis에 query ID 발행
    └─ B 개인 구독에 MESSAGE 1건

최종 RAG 상태: FAILED
시험 결론: 교차 노드 알림 경로는 관측 성공 / 답변 생성은 별도 실패
```

## 실행별 결과

| 실행 ID | 목적·방법 | 관측 | 판정·개별 로그 |
| --- | --- | --- | --- |
| `475-local-regression-01` | 로컬 Gradle 회귀 시험 첫 시도 | 샌드박스가 Gradle 캐시 잠금 파일을 거부, 테스트 0건 실행 | 환경 권한 실패. [로그](evidence/issue-475/475-local-regression-01.md) |
| `475-local-regression-02` | 동일 3개 테스트 클래스를 허용된 로컬 캐시에서 재실행 | 6/6 통과, 실패 0 | 통과. [로그](evidence/issue-475/475-local-regression-02.md) |
| `475-vercel-01` | 병합 프런트의 기존 Production 프로젝트 원격 빌드·배포 | 빌드 완료, READY·Production alias, 공개 `/search` 200; RAG 관련 번들에 10초 타이머 확인 | 배포 통과, 장시간 heartbeat 유지 시험은 별도. [로그](evidence/issue-475/475-vercel-01.md) |
| `475-app-a-01`, `475-app-b-01` | A 다음 B 순차 JAR 교체 | A/B 같은 SHA, 서비스 active, readiness 200, Worker off·교차 노드 신호 on | 배포 통과. [A](evidence/issue-475/475-app-a-01.md) · [B](evidence/issue-475/475-app-b-01.md) |
| `475-public-origin-01` | 실제 Vercel Origin으로 공개 WSS/JWT STOMP 접속 | 로그인 200, Upgrade 101, CONNECTED | 통과. [로그](evidence/issue-475/475-public-origin-01.md) |
| `475-public-rag-01` | 같은 공개 경로에서 SUBSCRIBE 후 검색 | MESSAGE 1건, 최종 RAG `FAILED` | 알림 경로 통과, 답변 생성 실패. [로그](evidence/issue-475/475-public-rag-01.md) |
| `475-crossnode-b-a-01` | B 소켓·A 검색 고정 첫 시도 | 관리 포트에 잘못 연결해 Upgrade 500, 조회·알림 0건 | 시험 배선 실패. [로그](evidence/issue-475/475-crossnode-b-a-01.md) |
| `475-crossnode-b-a-02` | 앱 포트로 터널을 고쳐 B 소켓·A 검색 고정 | A Worker `RAG-001` fallback 기록, B 소켓 MESSAGE 1건 | A→B 교차 전달 관측. [로그](evidence/issue-475/475-crossnode-b-a-02.md) |
| `475-cleanup-01` | A/B 해시·readiness 재확인 후 원복 타이머·임시 자격 제거 | A/B active·동일 SHA, 타이머 inactive, 비밀 포함 설정 백업 제거, 임시 OS Login 키 0개 | 정리 통과. [로그](evidence/issue-475/475-cleanup-01.md) |

## 해석과 한계

공개 Origin을 포함한 같은 병합 소스를 A/B와 프런트에 배포해야 한다. `CorsConfig` 변경 없는 RAG JAR의 Origin 없는 WebSocket 시험만으로 실제 브라우저 연결을 판정하면 안 된다. 이전 403은 발견 즉시 원복했고 이번 통합 배포에서 101을 다시 확인했다.

이번 결과는 **알림 전달**을 확인한 것이다. 공개 검색과 고정 B 소켓 시험 모두 RAG 최종 상태는 `FAILED`였고, A의 비식별 로그에는 `RAG-001` fallback이 있었다. Ollama 답변 생성 성공, Redis 단절 중 신호 보존, 역방향 B Worker→A 소켓, 장시간 heartbeat 유지, 동시 고부하는 확인하지 않았다. 공개 화면의 실제 브라우저 클릭 경로도 이번 재배포 직후에는 별도로 반복하지 않았으며, Origin을 명시한 JWT/STOMP 클라이언트로 검증했다.

이번 테스트에서는 앱의 인덱싱 Worker를 계속 비활성으로 유지했고 DB·Redis·GCS 데이터를 삭제하지 않았다. 검색 시험으로 생성된 합성 query는 기존 시험 DB에 남아 있다.
