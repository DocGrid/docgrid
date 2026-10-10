# 공개 화면 HTTP E2E와 인증 WebSocket 검증

- 관련 이슈: [#471](https://github.com/DocGrid/docgrid/issues/471)
- 기준 코드: `d28c202f01251a945410ef6598be8cfb48671d5e`의 범위 제한 Worker를 앱 A에 일시 배포, 시험 뒤 원래 JAR로 복원
- 실행일: 2026-10-10 KST
- 결론: **브라우저 업로드→인덱싱→검색→다운로드 통과. 인증 WebSocket 연결 통과, RAG 알림 수신 실패. RAG 답변 생성 실패.**

## 실제 경로와 판정 범위

```text
브라우저 사용자 ──▶ Vercel UI ──▶ 공개 API 진입점 ──▶ 앱 A/B
       │                                            ├─ OpenProxy → OpenSQL
       │                                            ├─ GCS 원본
       │                                            └─ 앱 A 범위 제한 Worker → CPU BGE-M3
       └─ 직접 WSS ──▶ 공개 진입점 ──▶ 앱 A 또는 B의 로컬 STOMP 브로커

HTTP 성공과 WSS 업그레이드는 별도 신호다. STOMP `CONNECTED`도 RAG 알림 `MESSAGE` 수신을 대체하지 않는다.
```

| 실행 ID | 목적·위치·방법 | 주요 관측 | 판정·증거 |
| --- | --- | --- | --- |
| `ui471-http-r1` | 공개 브라우저의 일반 USER 가입·로그인·합성 PDF 업로드·검색·다운로드 | 문서 #115·버전 #117·Job #116, 최종 `INDEXED`, 검색 2결과 중 시험 문서 1위, 원본/다운로드 17,208 bytes·SHA-256 일치 | HTTP E2E 통과. [개별 로그](evidence/issue-471/ui471-http-r1.md) |
| `worker471-scoped-r1` | 앱 A만 시험 버전 #117 Worker 활성·pgJDBC 전후 대조·원복 | Attempt 1·retry 0, 청크 1·벡터 1×1024, 중복 0, 기존 Job 81건 지문 동일, A/B Worker 비활성 복구 | 범위 제한 통과. [개별 로그](evidence/issue-471/worker471-scoped-r1.md) |
| `ws471-r1` | 공개 WSS 인증·구독 즉시 검색 | CONNECTED, 검색 200, MESSAGE 0, 약 31초 뒤 종료 | 알림 실패. [개별 로그](evidence/issue-471/ws471-r1.md) |
| `ws471-r2` | 구독 뒤 1초 대기 | CONNECTED, 검색 200, MESSAGE 0, 약 31초 뒤 종료 | 알림 실패. [개별 로그](evidence/issue-471/ws471-r2.md) |
| `ws471-r3` | 프런트와 같은 10초 heartbeat·90초 관측 | 연결 약 90초 유지·heartbeat 8회, MESSAGE 0 | 알림 실패. [개별 로그](evidence/issue-471/ws471-r3.md) |
| `ws471-r4` | 검색 query 최종 HTTP 상태와 WS 대조 | query #79 `PROCESSING→FAILED`; 인증 WS 90초 유지, MESSAGE 0 | RAG 실패·알림 부재 구분. [개별 로그](evidence/issue-471/ws471-r4.md) |

## 해석과 한계

1. 문서 처리와 검색은 실사용 공개 UI에서 성공했다. 이는 백엔드 API만 직접 호출한 이전 #469 시험보다 화면 경로를 한 단계 더 검증한 것이다. 다만 합성 PDF 한 건으로 모든 문서 형식을 보장하지 않는다.
2. 화면에는 한 조회 순간 문서 헤더 `INDEXING`·버전 `INDEXED`가 함께 보인 뒤 다음 조회에서 둘 다 `INDEXED`로 수렴했다. 읽기 경로·시점 차이의 가능성은 있으나 원인은 미확정이다.
3. RAG 답변은 화면과 HTTP 조회에서 `FAILED`였다. 이는 검색 인덱싱 실패가 아니다. Ollama 등 하위 원인은 이 실행에서 로그로 확정하지 않았다.
4. 인증 STOMP `CONNECTED`와 `SUBSCRIBE` 프레임 전송은 확인됐지만 receipt 또는 `MESSAGE`는 0건이다. 구독 등록 완료·이벤트 전달을 성공으로 주장할 수 없다. 앱 A/B의 각 SimpleBroker 사이에 RAG 알림 교차 노드 전파가 없다는 코드상 구조는 별도 원인 조사 대상이다.
5. 브라우저 시험 계정의 비밀번호는 로그·Git에 남기지 않았다. 클라이언트 스크립트의 단기 토큰과 임시 로컬 파일은 외부에 공개하지 않는다. 다른 검색 결과의 본문도 기록하지 않았다.

## 원복

앱 A는 기존 JAR SHA-256 `a321b1e6f09d8c805c8bd0f58f641916db9a565286cc7e2655d4bedd8ff5de89`, Worker 비활성, readiness 200으로 원복했다. 앱 B는 기존 JAR·Worker 비활성, readiness 200을 유지했다. 앱 A에 만든 설정 백업과 일회성 전송 파일은 제거했다. CPU 시험 VM의 임시 SSH 키는 메타데이터에서 제거했고 다른 메타데이터는 유지됐다. 공개 UI 문서·시험 계정과 CPU 모델 서비스는 삭제하지 않았다.
