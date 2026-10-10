# OpenSQL primary VM 장애 뒤 멱등 재전송 시연 절차

상태: **절차 초안 — GCP 장애·부하 미실행**. 새 코드의 로컬 계약·PostgreSQL 경합 검증과 실제 GCP 앱 배포·장애 시험은 다른 단계다. 기존 `/api/ha-probe/writes`는 중복 발견용으로 유지하며, 이 절차는 새 `/api/ha-probe/idempotent-writes`만 사용한다.

## 개념과 안전 관문

```text
k6 최초 쓰기 → 내부 LB → 앱 A/B → OpenProxy A/B → primary
      │                                             X VM 상실
      └─ 201 / 500 / 503 / 결과 불명 원장을 DB 밖에 보존

새 primary·앱 쓰기 복구 → 재전송 전 DB 조회 → 실패·불명만 같은 ID로 재전송
                         │                    ├─ 201: 이번에 새로 기록
                         │                    ├─ 200: 기존 기록 확인
                         │                    └─ 409: 내용 충돌, 즉시 중단
                         └─ 최초 201의 누락을 재전송 후 결과로 덮어쓰지 않음
```

장애 주입 전 새 JAR·V45 적용, 앱 A/B·LB 건강, Patroni primary 1·replica 2, etcd 3/3, 복제 상태, 현재 백업과 **노트북과 독립적인 VM 재시작 가드**를 모두 재확인한다. 한 항목이라도 불확실하면 VM을 중단하지 않는다. 이번 시연은 리더 VM 한 대에 OpenProxy가 같이 있다면 복합 장애라는 점도 기록한다. 기존 시험의 30 req/s 설정을 무조건 재사용하지 말고 신규 정상 기준선에서 k6 미전송 0건을 확인한다.

## 터미널 배치

| 터미널 | 위치 | 역할 |
| --- | --- | --- |
| A | 운영자 컴퓨터 | 가드·클러스터 상태 확인, 장애 주입·복구 확인 |
| B | GCP 부하 VM | k6 최초 부하, 안전 원장, 제한적 재전송 |
| C | 현재 primary DB VM | 재전송 전·후 `request_id` 행 수를 읽기 전용으로 내보내기 |
| 브라우저 | Grafana | 역할·프록시 포트·HTTP 최초 응답과 회복 표시. 재전송의 최종 DB 판정은 별도 요약 파일로 보여주기 |

실제 내부 주소·VM명·JWT 경로는 운영자가 해당 환경에서 확인해 셸 변수로 지정한다. 채팅·Git·촬영 화면·시험 로그에 값 자체를 적지 않는다.

## 순서별 명령 템플릿

아래의 `RUN_ID`, `TARGET`, `JWT_FILE`, `RUN_DIR`, `PRIMARY_CONTAINER`는 **예시 변수명**이며 실제 값이 아니다. 명령은 해당 VM에 필요한 스크립트가 배치된 뒤 실행한다.

### 1. 최초 부하 — 터미널 B

새 실행 ID의 정상 기준선을 먼저 완료하고, 장애 실행에는 다시 새 ID를 쓴다. 아래는 안전 관문 통과 뒤의 **장애 실행** 예시다. 부하가 실제 시작돼 201/s가 보인 뒤에만 터미널 A에서 현재 리더를 다시 확인하고 VM 장애를 주입한다.

```bash
./scripts/opensql/run_ha_probe_k6.sh "$RUN_ID" 30 180s "$TARGET" "$JWT_FILE" primary-vm-idempotent 80
```

최초 실행의 k6 종료 코드 99는 장애 중 실패율 임계값 초과일 수 있으므로 원장·요약을 확인하기 전에는 시험 자체를 무효로 단정하지 않는다. 미전송과 실제 HTTP 실패를 구분한다.

### 2. 최초 원장 비식별화 — 터미널 B

```bash
python3 scripts/opensql/sanitize_ha_k6_events.py \
  --source "$RUN_DIR/k6-events.jsonl" \
  --output "$RUN_DIR/k6-events-safe.jsonl" \
  --run-id "$RUN_ID"
```

### 3. 재전송 **전** DB export — 터미널 C

새 primary임을 Patroni와 `pg_is_in_recovery()=false`로 확인하고, 아래 결과를 보호된 파일로 저장한다. 부하 VM에서 재전송 도구를 실행하기 전에 이 CSV를 암호화된 관리 경로로 터미널 B에 전달한다.

```bash
umask 077

sudo ./scripts/opensql/export_ha_probe_counts.sh \
  "$PRIMARY_CONTAINER" "$RUN_ID" idempotent > db-before.csv
```

### 4. 제한적 재전송 — 터미널 B

`db-before.csv`를 `$RUN_DIR/db-before.csv`에 둔 뒤 실행한다. 도구가 `재전송-전-대조.json`을 먼저 기록한 다음, 최초 5xx·결과 불명 요청만 같은 ID·내용으로 추가 최대 3회, 최대 20건/s로 보낸다. `201`·`401/403` 최초 요청은 보내지 않는다. 재전송 중 3xx·4xx·409가 나오면 추가 전송을 멈춘다.

```bash
python3 scripts/opensql/retry_idempotent_ha_probe.py run \
  --run-id "$RUN_ID" \
  --events "$RUN_DIR/k6-events-safe.jsonl" \
  --summary "$RUN_DIR/k6-summary.json" \
  --db-before "$RUN_DIR/db-before.csv" \
  --target "$TARGET" \
  --token-file "$JWT_FILE" \
  --output-dir "$RUN_DIR/replay-01"
```

### 5. 최종 DB export·대조 — 터미널 C → B

터미널 C에서 같은 read-only export를 `db-after.csv`로 다시 실행해 터미널 B의 `$RUN_DIR/db-after.csv`에 전달한다. 그다음 터미널 B에서 다음 명령을 실행한다.

```bash
python3 scripts/opensql/retry_idempotent_ha_probe.py verify \
  --run-id "$RUN_ID" \
  --events "$RUN_DIR/k6-events-safe.jsonl" \
  --summary "$RUN_DIR/k6-summary.json" \
  --db-before "$RUN_DIR/db-before.csv" \
  --db-after "$RUN_DIR/db-after.csv" \
  --replay-dir "$RUN_DIR/replay-01"
```

최종 화면에서는 **최초 201·500·503·불명**, **재전송 201·200·409·미해결**, **재전송 전 201 누락**, **최종 DB 행·중복**을 각각 보여준다. 성공 기준은 첫 장애의 500이 0이라는 뜻이 아니라, 실패·불명 요청이 복구 뒤 안전하게 수렴하고 최초 성공 응답의 누락·중복이 없다는 뜻이다. 비동기 복제에서 모든 향후 장애의 RPO 0을 보장하지 않는다.

JWT 만료·중간 중단·가드 실패 시 이 실행을 부분 실행으로 보존한다. 같은 증거 폴더를 덮어쓰거나 기존 201을 재전송하지 않는다. 원인과 클러스터 상태를 복구한 뒤 새 실행 ID로 처음부터 재시도한다. 시험 뒤 VM 재합류, primary 1·replica 2, etcd 3/3, 앱·LB 건강, 임시 권한·JWT·가드 정리를 별도로 확인한다.
