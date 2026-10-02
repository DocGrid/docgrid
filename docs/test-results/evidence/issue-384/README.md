# 대시보드 WebSocket 재연결 시험 원장

모든 시각은 원본 JSONL의 KST(UTC+09:00)다. 각 실행의 목적·성공 기준·관측·해석은 같은 이름의 `-summary.md`에 따로 기록했다. JSONL은 수집 시점부터 상태·건수·시간·HTTP 상태만 허용해 적었고 토큰, 암호, 내부 주소, 프로젝트 식별자는 기록하지 않았다.

| 실행 | 구분 | 결과 | 원본 / 설명 |
|---|---|---|---|
| `fix384-gcp-reconnect-r1` | 직접 IAP 포트 사전 연결 | 환경 실패, 판정 제외 | [JSONL](./fix384-gcp-reconnect-r1.jsonl) / [설명](./fix384-gcp-reconnect-r1-summary.md) |
| `fix384-gcp-reconnect-r2` | B 재시작 첫 측정 | 제품 복구, 하네스 종료 오류 | [JSONL](./fix384-gcp-reconnect-r2.jsonl) / [설명](./fix384-gcp-reconnect-r2-summary.md) |
| `fix384-gcp-reconnect-r3` | B 재시작 재실행 | PASS | [JSONL](./fix384-gcp-reconnect-r3.jsonl) / [설명](./fix384-gcp-reconnect-r3-summary.md) |
| `fix384-gcp-redis-observer-r2` | Redis 중단, B 구독 5개 | 5개 연결 종료 관측 | [JSONL](./fix384-gcp-redis-observer-r2.jsonl) / [요약 JSON](./fix384-gcp-redis-observer-r2-summary.json) / [설명](./fix384-gcp-redis-observer-r2-summary.md) |
| `fix384-gcp-redis-client-r1` | Redis 중단, 최초 제어기 | FAIL, 180초 미복구 | [JSONL](./fix384-gcp-redis-client-r1.jsonl) / [설명](./fix384-gcp-redis-client-r1-summary.md) |
| `fix384-gcp-redis-client-r2` | Redis 중단, 수정 제어기 | PASS, 6,942ms 복구 | [JSONL](./fix384-gcp-redis-client-r2.jsonl) / [설명](./fix384-gcp-redis-client-r2-summary.md) |

로컬 재실행은 [unit](./fix384-final-unit-r2.md), [전체 프런트엔드 suite](./fix384-final-suite-r2.md), [lint](./fix384-final-lint-r2.md), [변경 소스 타입](./fix384-final-type-r2.md), [하네스 문법](./fix384-harness-syntax-r1.md), [전체 타입 실패](./fix384-full-typecheck-r2.md)로 목적별로 분리했다. 이 로컬 문서는 원본 터미널 출력이 아니라 도구 출력에서 확인한 허용 목록 수치만 기록한 결과 요약이며, 원본 출력을 보존한 것처럼 취급하지 않는다.

초기 Redis 관측 준비에서 구버전 관측 스크립트에 `--count-only`가 없어 두 번 `argparse` 종료 코드 2가 발생했다. 그 두 실행에는 측정 표본이나 보존된 원본 로그가 없다. 현재 스크립트를 관측 VM에 복사해 사전 구독 1개·메시지 6개 확인 후 `fix384-gcp-redis-observer-r2`를 실행했으며, 준비 실패를 제품 장애 결과에 포함하지 않았다.
