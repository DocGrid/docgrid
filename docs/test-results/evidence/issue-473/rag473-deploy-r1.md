# 앱 A/B 교차 알림 배포 — `rag473-deploy-r1`

- 목적: 공개 시험 앱 A/B에 동일 Fix JAR·Redis 신호 설정을 적용하고 Worker 정책·접속 건강을 유지한다.
- 위치·시각: GCP 시험용 앱 A/B VM, 2026-10-10 약 05:40~05:45 KST.
- 완료 기준: 새 JAR 해시 동일, `RAG_ANSWER_CROSS_NODE_ENABLED=true`, `INDEXING_WORKER_ENABLED=false`, readiness 200·서비스 active 각각 2/2.

| 단계·위치 | 명령·방법 | 결과 | 판정 |
| --- | --- | --- | --- |
| 사전 점검 A/B | IAP SSH로 JAR SHA·Worker 설정·서비스만 읽음 | 둘 다 기존 JAR `a321b1e6f09d8c805c8bd0f58f641916db9a565286cc7e2655d4bedd8ff5de89`, Worker off, RAG 신호 미설정, 서비스 active | 동일 기준선 |
| 배포 A | IAP SCP로 JAR·원복 스크립트 전송, 해시 일치 후 JAR과 설정 백업→새 JAR 설치→신호 on→재시작 | 새 JAR `64dae86ab1b683f911900ce99e1e2441d2bff4d1fde6a31575e6fa07275c3f36`, Worker off, readiness 200 | A 통과 |
| 배포 B | A와 동일, A 통과 뒤 순차 적용 | 같은 JAR 해시·설정, Worker off, readiness 200 | B 통과 |

원복 가드는 재시작·readiness 실패 시 이전 JAR과 소유자 전용 설정 파일을 복원한다. 시험이 끝날 때까지 앱 A/B는 새 JAR으로 유지했고 DB VM·Redis VM 설정은 바꾸지 않았다. 백업 설정 파일은 중복 비밀을 담으므로 최종 검증 뒤 제거한다.
