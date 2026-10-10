# OpenSQL 리더 VM 장애: 전체 전송 ID의 k6 멱등 재전송

상태: **도구 구현·로컬 합성 시험 완료, 실제 GCP 장애/부하 미실행.** 기존 `retry_idempotent_ha_probe.py run`의 **5xx·결과 불명만 제한적으로 재전송**하는 동작은 그대로 유지한다. 이 문서는 별도 `prepare-k6-all` → k6 → `verify-k6-all` 모드다. 실제 서비스의 자동 재시도 정책이 아니라, 같은 본문·같은 ID를 다시 보냈을 때 멱등 계약을 검증하는 시험이다.

## 무엇을 찍나

```text
첫 영상: 30 req/s 최초 부하 → 리더 VM 상실 → 새 primary 선출 → 최초 k6 종료
                                      │
                         독립 복구 가드는 계속 작동
                                      │
촬영 잠시 중지: 새 primary의 DB를 재전송 전에 request_id별 CSV로 보존
              원본 안전 원장과 DB를 대조하고 전체 전송 ID 목록을 동결
                                      │
둘째 영상: 같은 run_id·request_id·payload를 k6 30 req/s로 각 1번 재전송
           200=이미 있던 행, 201=새 행, 기타/불명=미해결로 별도 기록
                                      │
마지막 증거: 새 primary의 재전송 후 DB CSV → ID별 1행·누락 0 비교
```

영상은 두 파일로 나눠도 된다. 다만 중간에 시스템이 멈춘 것은 아니므로 **독립 VM 복구 가드와 관측은 끄지 않는다.** 둘째 영상 시작에 UTC 시각, 현재 primary, `pg_is_in_recovery()=false`, 원본 실행 ID를 다시 보여준다. 두 구간 사이에 다른 primary 전환이나 쓰기가 있었으면 기록한다. 편집으로 연속 촬영처럼 보이게 하지 않는다.

## 안전·범위

- 리더 VM 중단 전에 앱 A/B, Patroni primary 1·replica 2, etcd 3/3, OpenProxy A/B, 최신 복구 수단, **운영자 Mac과 독립적인 현재 리더 VM 전용 복구 가드**를 재확인한다. 하나라도 불명확하면 장애 주입을 하지 않는다.
- 최초 부하에는 `primary-vm-idempotent`를 쓰고, 도구가 만든 `k6-events-safe.jsonl`의 **실제로 보낸 ID만** 전체 재전송한다. k6 미전송 반복에는 request ID가 없어 재전송할 수 없다. 최초 미전송은 별도 실패/한계로 남긴다.
- 최초 401/403도 이 **시험용 전체 재전송**에는 포함된다. 새 JWT로 200 또는 201을 받더라도 최초 인증 실패 사실이 사라지는 것은 아니다. 제품 재시도 정책에서는 401/403을 자동 재시도하지 않는다.
- `db-before.csv`를 **재전송보다 먼저** 새 primary에서 내보낸다. 최초 201의 사전 DB 누락은 최종 DB에 다시 생겨도 `final_pass=false`로 남는다. `idempotency_pass`는 별도 판정한다.
- JWT·내부 LB URL·VM명·내부 IP·프로젝트 ID는 채팅, 로그, 화면, 저장소에 적지 않는다. 아래는 변수 자리만 표시한 템플릿이다. 파일은 권한 0600, 실행별 디렉터리는 0700을 유지한다.
- 현재 Grafana의 기존 k6 카드는 **최초 부하 지표**다. 이 새 k6 재전송은 별도 지표/원장으로 보존되며, 현재 대시보드 2xx 카드가 재전송 중 증가한다고 기대하면 안 된다. 둘째 영상에는 부하 VM의 안전한 진행 합계와 마지막 DB 대조를 보여준다.

## 화면과 터미널

| 화면 | 실행 위치 | 무엇을 보여주나 |
| --- | --- | --- |
| Grafana 브라우저 | 운영자 Mac | 첫 영상의 primary 역할·timeline·프록시·최초 HTTP 응답. 둘째 영상의 현재 클러스터 상태 |
| 터미널 A | 운영자 Mac | VM 가드 확인과 장애 주입/복구 상태. 리더 VM 안에서 VM 중단 명령을 실행하지 않음 |
| 터미널 B | GCP 부하 VM | 최초 k6, 전체 ID 목록 준비, k6 재전송의 `완료/200/201/기타/불명` 진행 숫자 |
| 터미널 C | **그 시점의** primary DB VM | 재전송 전·후 `request_id,row_count` 읽기 전용 export. 노드명은 선출 결과에 따라 달라짐 |

## 명령 템플릿

다음 변수는 환경에서 **직접 확인한 값**으로 채운다. 기존 첫 실행 결과 폴더의 경로는 runner 출력과 부하 VM의 보호된 실행 폴더에서 확인한다. 이 문서의 경로나 사용자명을 사실로 가정하지 않는다.

### 1. 첫 영상 — 부하 VM의 터미널 B

새 실행 ID를 정하고, 30 req/s·180초 최초 부하를 시작한다. 정상 쓰기 그래프를 확인한 뒤에만 Mac 터미널 A에서 **실제 현재 리더를 다시 조회하고**, 준비된 가드의 대상과 일치할 때 VM 상실을 주입한다. 아래 부하 명령은 원본 도구이며 재전송 명령이 아니다.

```bash
bash scripts/opensql/run_ha_probe_k6.sh "$RUN_ID" 30 180s "$TARGET" "$JWT_FILE" primary-vm-idempotent 80
```

부하 종료 후 원본 폴더를 확인하고 다음 값을 지정한다. `RUN_DIR`은 실제 그 실행의 디렉터리다. 기존 경로를 외워 입력하지 않는다.

```bash
printf '실행 ID=%s\n' "$RUN_ID"
```

```bash
find "$HOME" -maxdepth 4 -type f -name k6-summary.json -print
```

```bash
RUN_DIR='<방금 확인한 이번 실행 디렉터리>'
```

### 2. 촬영 잠시 중지 — 터미널 B에서 원본 안전 원장 생성

```bash
python3 scripts/opensql/sanitize_ha_k6_events.py \
  --source "$RUN_DIR/k6-events.jsonl" \
  --output "$RUN_DIR/k6-events-safe.jsonl" \
  --run-id "$RUN_ID"
```

### 3. **재전송 전** DB 대조 — 새 primary의 터미널 C

Patroni의 현재 primary와 PostgreSQL `pg_is_in_recovery()=false`가 일치해야 한다. 아래 export는 primary가 아니면 실패한다. `db-before.csv`는 개인 권한 파일로 보존하고, 승인된 관리 경로로 부하 VM의 `$RUN_DIR/db-before.csv`에 옮긴다. 복사 전후 체크섬을 대조한다.

```bash
umask 077
```

```bash
sudo bash scripts/opensql/export_ha_probe_counts.sh \
  "$PRIMARY_CONTAINER" "$RUN_ID" idempotent > db-before.csv
```

### 4. 전체 ID 동결 — 부하 VM의 터미널 B

```bash
python3 scripts/opensql/retry_idempotent_ha_probe.py prepare-k6-all \
  --run-id "$RUN_ID" \
  --events "$RUN_DIR/k6-events-safe.jsonl" \
  --summary "$RUN_DIR/k6-summary.json" \
  --db-before "$RUN_DIR/db-before.csv" \
  --output-dir "$RUN_DIR/replay-all-01"
```

이 명령이 원본 원장·k6 요약·DB 사전 CSV의 일치 여부를 먼저 확인하고, `재전송-전체-목록.json`에 **전송된 모든 ID**를 한 번씩 동결한다. 실패하면 재전송 명령을 실행하지 않는다. 같은 폴더 이름을 재사용하거나 기존 증거를 덮어쓰지 않는다.

### 5. 둘째 영상 — 부하 VM의 터미널 B

영상 시작에 새 primary 역할과 UTC 시각을 다시 보여준다. 실행기는 JWT의 `exp`가 예상 재전송 시간에 **90초 여유**를 더한 시점보다 늦은지 검사한다. 이것은 만료 방지용 사전 검사일 뿐, JWT의 서명·역할·서버 시간까지 보증하지 않는다. 부족하거나 해석할 수 없으면 요청 0건으로 중단하므로 새 토큰을 비공개로 발급받아 다시 확인한다. 다음 명령으로 **같은 ID·본문을 30 req/s**로 보낸다. 원본 `run_ha_probe_k6.sh`를 다시 실행하면 새 ID가 생성되므로 사용하지 않는다. `160`은 초기 VU이며 필요하면 사전 기준선에서 검증한 값으로만 조정한다.

```bash
bash scripts/opensql/run_ha_probe_replay_all_k6.sh \
  "$RUN_DIR/replay-all-01" "$TARGET" "$JWT_FILE" 30 160
```

터미널에는 약 5초마다 완료·200·201·기타·불명의 **집계 숫자만** 표시된다. k6 자체의 원시 오류는 비밀정보 제거를 보장할 수 없어 화면과 로그에 복사하지 않는다. HTTP 401/409, 미전송, 불명이 나오면 그 사실을 그대로 남기고 최종 DB 판정에서 실패로 처리한다.

### 6. 재전송 후 DB 대조 — 현재 primary의 터미널 C, 그다음 부하 VM B

```bash
sudo bash scripts/opensql/export_ha_probe_counts.sh \
  "$PRIMARY_CONTAINER" "$RUN_ID" idempotent > db-after.csv
```

`db-after.csv`도 승인된 관리 경로로 부하 VM의 `$RUN_DIR/db-after.csv`에 복사해 체크섬을 확인한다.

```bash
python3 scripts/opensql/retry_idempotent_ha_probe.py verify-k6-all \
  --run-id "$RUN_ID" \
  --events "$RUN_DIR/k6-events-safe.jsonl" \
  --summary "$RUN_DIR/k6-summary.json" \
  --db-before "$RUN_DIR/db-before.csv" \
  --db-after "$RUN_DIR/db-after.csv" \
  --replay-dir "$RUN_DIR/replay-all-01" \
  --replay-summary "$RUN_DIR/replay-all-01/k6-replay-summary.json"
```

최종 `재전송-최종-대조.json`의 `idempotency_pass`, `final_pass`, `initial_201_missing_before_retry`, `k6_replay_evidence_valid`, `db_before_rows`, `final_db_rows`, `db_rows_added`, `final_missing_sent_ids`, `db_duplicate_ids_after_retry`를 함께 보여준다. `db_rows_added`는 **새로 저장된 ID 수**다. 전체 재전송 요청 수와 같다고 가정하지 않는다. 이미 저장돼 있던 ID는 재전송 후 200, 없던 ID는 201이 기대된다.

중간 실패 시 원본·사전 DB CSV·부분 재전송 원장을 보존한다. 재전송을 같은 폴더에서 무작정 재개하지 말고, 클러스터/가드/JWT 원인을 확인한 뒤 새 실행 ID의 처음 단계부터 다시 시작한다. 시험 후에는 VM 재합류, primary 1·replica 2, etcd 3/3, 앱/프록시/관측 건강과 임시 권한·JWT·가드 정리를 별도 확인한다.
