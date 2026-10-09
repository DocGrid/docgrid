# OpenSQL HA 정상 부하·Grafana 리허설: 직접 따라 하는 안내

작성일: 2026-10-09 KST · 범위: 정상 쓰기, Grafana 표시, 요청 ID별 DB 대조 · 장애 주입: **미실행**

## 결론

GCP 내부 부하 VM에서 **30 req/s × 60초**를 실행했다. `ha449run0001`의 실제 HTTP 요청 **1,801건은 전부 201**, 미전송 **0건**, Prometheus의 최종 2xx Counter **1,801건**, 현재 primary DB의 요청 ID **1,801개·1,801행**이었다. ID별 누락·중복·고아 DB 행은 모두 **0건**이다. [5초 스모크](evidence/issue-448/ha449smk0001/실행-요약.md)와 [1분 기준선](evidence/issue-448/ha449run0001/실행-요약.md)의 로그·원본은 따로 보관했다.

Grafana에서 이 실행 ID를 선택하니 **2xx 1,801 / 3xx 0 / 4xx 0 / 5xx 0**, HTTP 201/초 그래프와 활성 VU 그래프가 실제로 나왔다. 첨부 문서의 “정상 201 미실행”은 **2026-10-08 당시**의 기록이다. 오늘은 그 관문만 완료했으며 프록시·primary 장애는 발생시키지 않았다.

기존 13002 IAP 터널이 열려 있으면 [이번 실행의 Grafana 시간 구간](http://localhost:13002/d/docgrid-opensql-ha-live/docgrid-opensql-ha-live?from=1791532220000&to=1791532310000&timezone=browser&var-run_id=ha449run0001)에서 바로 볼 수 있다. 터널이 닫혔으면 아래 터미널 A부터 다시 연다. 이 링크의 시간 범위는 **16:50:20~16:51:50 KST**이며, 새 시험을 볼 때는 실행 ID와 시간 범위를 바꿔야 한다.

## 1. 먼저 개념을 그림으로 보기

그림 1. 필요한 화면은 **터미널 3개 + 브라우저 1개**다.

```text
내 Mac
├─ 터미널 A: 관측 VM까지 Grafana IAP 터널을 계속 유지
│     13002(local) ──암호화 SSH──▶ Grafana 3000
│
├─ 터미널 B: GCP 대상 확인·시험 계정/JWT 준비·DB 원장 대조·정리
│     DB 후보 3대에 자동 정리 타이머 → 현재 primary에 합성 ADMIN 생성
│     → 20분 JWT 생성·암호화 전송 → 종료 후 DB 대조·계정/토큰 제거
│
└─ 터미널 C: 부하 VM SSH 셸
      배포 스크립트 확인 → k6 5초 스모크 → 별도 ID로 1분 기준선
      → 안전 이벤트·1초 계측·원격 지표 대조 생성

브라우저: Grafana에서 실행 ID 선택, 카드와 그래프 관찰
```

DB VM마다 터미널을 하나씩 더 띄울 필요는 없다. 터미널 B가 SSH로 세 노드를 확인한다. 각 터미널의 쉘 변수는 자동 공유되지 않으니, 실행 ID와 임시 키 경로는 **내 컴퓨터 안에서만** 맞춰야 한다.

그림 2. 한 HTTP 쓰기가 DB와 그래프로 갈라지는 경로다.

```text
부하 VM ha_probe_load.js
  ├─ 실행 ID + VU + 반복 번호 → request_id 생성
  └─ ADMIN JWT를 붙여 POST /api/ha-probe/writes
                │
                ▼
       내부 LB → 앱 A 또는 B → Spring Security(ADMIN 확인)
                                    │
                                    ▼
                    HaProbeWriteController.write()
                                    │ TX_BEGIN
                                    ▼
                    HaProbeWriteService.write()
                                    │ Hikari → OpenProxy → 현재 primary
                                    └─ ha_probe_writes INSERT·커밋
                │
                ├─ HTTP 201 → k6 원장 acknowledged
                ├─ k6 Counter → Remote Write → Prometheus → Grafana
                └─ 종료 후 DB request_id별 행 수를 원장과 대조
```

[k6 스크립트](https://github.com/DocGrid/docgrid/blob/765a87bb33e90b87ebfa4f04dbfe49003addf595/scripts/opensql/ha_probe_load.js), [컨트롤러](https://github.com/DocGrid/docgrid/blob/765a87bb33e90b87ebfa4f04dbfe49003addf595/backend/src/main/java/com/opensource/docgrid/domain/failover/controller/HaProbeWriteController.java), [쓰기 서비스](https://github.com/DocGrid/docgrid/blob/765a87bb33e90b87ebfa4f04dbfe49003addf595/backend/src/main/java/com/opensource/docgrid/domain/failover/service/command/HaProbeWriteService.java)가 실제 이 경로다. 기존 HA probe는 요청 ID의 **중복 저장을 자동 차단하지 않는다**. 이번 “중복 0”은 사후 DB 대조 결과다.

그림 3. 시험 계정과 비밀은 부하보다 먼저 안전망을 만들고, 실패해도 정리한다.

```text
새 run ID
  ├─ DB VM 3대에 독립 자동 정리 타이머 3/3 무장
  │     실패하면 계정 생성 금지
  ├─ 현재 primary에 합성 시험 계정 4명 생성
  ├─ ADMIN ID로 20분 JWT 파일(0600) 생성
  └─ 암호화 SCP → 부하 VM의 0600 파일
                  │
                  ▼
          5초 스모크 / 1분 기준선
                  │
                  ▼
       결과가 성공이든 실패든 요청별 DB CSV 추출
                  │
                  ▼
       계정 4→0, 타이머 3→0, JWT·임시 키 제거
```

아래 `<...>`는 사용자가 자기 터미널에서 채우는 자리다. 내부 IP·프로젝트 ID·JWT·ADMIN ID는 채팅·Git·공개 로그에 쓰지 않는다. 원시 k6 stderr는 기록 전에 안전하게 가릴 수 없어 이번에도 수집하지 않았다.

## 2. 실행 전에 아는 용어

| 용어 | 쉬운 뜻 | 이번 판정에서 주의할 점 |
| --- | --- | --- |
| primary / replica | 쓰기를 받는 DB 한 대 / 내용을 따라가는 DB 두 대 | Patroni의 역할 표시와 HTTP 쓰기 성공은 별개 |
| OpenProxy | 앱 DB 연결을 현재 primary로 보내는 경로 | 포트 probe 2/2는 TCP 접속 가능이지 SQL 성공 보장이 아님 |
| VU / dropped iteration | k6의 가상 사용자 / 목표 시각에 시작하지 못한 반복 | VU는 DB 연결 수가 아님. 이번 초기 VU 80, 미전송 0 |
| run ID / request ID | 한 실행의 이름 / 그 안의 개별 쓰기 이름 | Grafana는 반드시 해당 run ID를 선택; All은 다른 실행까지 합산 |

## 3. 어느 터미널에서 무슨 명령을 치나

### 터미널 A — Grafana 터널

먼저 `lsof -nP -iTCP:13002 -sTCP:LISTEN`으로 포트를 확인한다. 이미 본인의 Grafana 터널이 있으면 그대로 사용한다. 다른 프로그램이 점유했다면 13003 등 빈 포트로 **명령과 URL을 함께** 바꾼다.

```bash
ZONE='us-central1-a'
OBSERVER_VM='<관측 VM 이름>'
gcloud compute ssh "$OBSERVER_VM" --zone="$ZONE" --tunnel-through-iap -- -N -L 13002:127.0.0.1:3000
```

이 명령은 아무 결과 없이 계속 실행 중인 것이 정상이다. 닫지 말고 브라우저에서 `http://localhost:13002/d/docgrid-opensql-ha-live/docgrid-opensql-ha-live`를 연다. Grafana 3000 포트를 인터넷에 공개하지 않는다.

### 터미널 B — 계정·JWT·결과 대조

저장소 루트에서 시작한다. GCP 대상 계정·프로젝트를 **개인 화면에서 확인**한다.

```bash
cd <DocGrid 저장소 루트>
gcloud auth list --filter=status:ACTIVE
gcloud config get-value project
gcloud compute instances list --format='table(name,zone.basename(),status)'
```

오늘은 관련 앱 A/B·부하·관측·캐시·DB 3대가 RUNNING, 내부 LB 백엔드 2/2가 HEALTHY였다. VM RUNNING만으로 201 쓰기 성공을 말하지 않는다. 필요하면 `gcloud compute backend-services get-health '<내부 LB 백엔드 서비스 이름>' --region='<리전>'`으로 다시 확인한다.

오늘 사용한 접근 방식은 **1시간 만료 OS Login 공개키**였다. 기존 정상 SSH 키가 있으면 재발급은 생략한다. 새 키를 쓸 경우 시험 종료 즉시 제거한다.

```bash
umask 077
ACCESS_DIR="$(mktemp -d)"
ssh-keygen -q -t ed25519 -N '' -f "$ACCESS_DIR/key"
ZONE='us-central1-a'
LOAD_VM='<부하 VM 이름>'
gcloud compute ssh "$LOAD_VM" --zone="$ZONE" --tunnel-through-iap \
  --ssh-key-file="$ACCESS_DIR/key" --ssh-key-expire-after=1h --command='true'
```

실행 ID는 새 12글자를 쓴다. [정리 도구](https://github.com/DocGrid/docgrid/blob/765a87bb33e90b87ebfa4f04dbfe49003addf595/scripts/opensql/ha_fixture_cleanup.py)가 활성 GCP 대상과 DB 3대의 역할을 확인하고 타이머를 모두 무장한 뒤에만 시험 계정을 만든다.

```bash
RUN_ID="ha$(date -u +%m%d%H%M%S)"
export OPENSQL_EXPECTED_ACCOUNT="$(gcloud auth list --filter=status:ACTIVE --format='value(account)')"
export OPENSQL_EXPECTED_PROJECT="$(gcloud config get-value project)"
export OPENSQL_GCP_ZONE="$ZONE"
export OPENSQL_SSH_KEY="$ACCESS_DIR/key"
python3 scripts/opensql/ha_fixture_cleanup.py prepare "$RUN_ID" --guard-seconds 1200
python3 scripts/opensql/ha_fixture_cleanup.py status "$RUN_ID" --guard-seconds 1200
```

`prepare`는 “시험 계정=4명, 타이머=3/3 무장”을 출력해야 한다. `status`에서 primary가 정확히 한 대인지 보고, 그 VM을 다음 변수에 적는다. 오늘의 primary가 3번 노드였다고 **다음에도 같다고 가정하면 안 된다**.

```bash
PRIMARY_VM='<status에서 확인한 현재 primary VM>'
ADMIN_ID="$(gcloud compute ssh "$PRIMARY_VM" --zone="$ZONE" --tunnel-through-iap \
  --ssh-key-file="$ACCESS_DIR/key" \
  --command="sudo -n /usr/local/sbin/docgrid-permission-fixture status '$PRIMARY_VM' '$RUN_ID' 1200" \
  | awk -F, -v name="ha-$RUN_ID-admin" '$1==name {print $2}')"
[[ "$ADMIN_ID" =~ ^[0-9]+$ ]] && echo 'ADMIN 조회 성공'
test -f .env && echo '보호된 로컬 설정 파일 확인'
```

두 확인 메시지가 모두 나와야 다음 블록으로 간다. 작업용 Git worktree에 `.env`가 없을 수 있으므로, 비밀 파일이 보관된 별도의 승인된 로컬 checkout에서 JWT 생성 명령만 실행해도 된다. 비밀 파일을 새 worktree나 Git에 복사하지 않는다.

```bash
TOKEN_DIR="$(mktemp -d)"
python3 scripts/opensql/create_ha_probe_jwt.py --env-file .env \
  --user-id "$ADMIN_ID" --subject "ha-$RUN_ID-admin@invalid.example" \
  --output "$TOKEN_DIR/jwt" --ttl-seconds 1200
gcloud compute scp "$TOKEN_DIR/jwt" "$LOAD_VM:/tmp/$RUN_ID.jwt" \
  --zone="$ZONE" --tunnel-through-iap --ssh-key-file="$ACCESS_DIR/key"
gcloud compute ssh "$LOAD_VM" --zone="$ZONE" --tunnel-through-iap \
  --ssh-key-file="$ACCESS_DIR/key" --command="stat -c '%a' /tmp/$RUN_ID.jwt"
```

ADMIN 조회 또는 보호된 설정 확인 메시지가 없으면 **이후 명령을 실행하지 말고 아래의 정리 단계로 간다.** 마지막 파일 권한 출력은 **600**이어야 한다. JWT 자체를 `cat`하거나 터미널 로그에 남기지 않는다. JWT와 독립 타이머의 유효 기간이 20분이므로, 준비가 길어졌다면 새 ID로 처음부터 다시 한다.

### 터미널 C — 부하 VM

새 로컬 터미널을 열어 B에서 만든 **실행 ID와 임시 키 디렉터리 경로만 내 컴퓨터 안에서** 옮긴다. SSH 접속 뒤에는 프롬프트가 부하 VM임을 확인한다.

```bash
ZONE='us-central1-a'
LOAD_VM='<부하 VM 이름>'
ACCESS_DIR='<터미널 B의 임시 키 디렉터리>'
gcloud compute ssh "$LOAD_VM" --zone="$ZONE" --tunnel-through-iap --ssh-key-file="$ACCESS_DIR/key"
```

이하 명령은 **부하 VM 안에서** 실행한다. 동명 백업 스크립트가 있으므로 현행 `$HOME/ha-scripts`를 선택하고, Mac 저장소 원본의 SHA-256과 비교한다.

```bash
RUN_ID='<터미널 B와 똑같은 실행 ID>'
SCRIPT_DIR="$HOME/ha-scripts"
TOKEN_FILE="/tmp/$RUN_ID.jwt"
test -r "$SCRIPT_DIR/run_ha_probe_k6.sh"
test "$(stat -c '%a' "$TOKEN_FILE")" = 600
sha256sum "$SCRIPT_DIR/run_ha_probe_k6.sh" "$SCRIPT_DIR/ha_probe_load.js"
```

터미널 B에서는 `shasum -a 256 scripts/opensql/run_ha_probe_k6.sh scripts/opensql/ha_probe_load.js`를 실행한다. 이번 실제 원본과 배포 파일은 각각 SHA-256 앞자리 `9a1a10c7…`, `736f82bf…`가 일치했다. **다른 해시면 시험을 시작하지 않는다.**

내부 LB 주소는 GCP 포워딩 규칙에서 본인이 확인하되 공개 문서에는 넣지 않는다. 부하 VM에서 비표시 입력으로 받아 사용한다. 관측 VM 내부 DNS는 이번 환경에서 조회됐다.

```bash
read -rsp '내부 LB IPv4(화면에 표시 안 됨): ' LB_IP; echo
OBSERVER_VM='<관측 VM 내부 DNS 이름>'
OBS_IP="$(getent ahostsv4 "$OBSERVER_VM" | awk 'NR==1 {print $1}')"
test -n "$LB_IP" && test -n "$OBS_IP"
LB_URL="http://$LB_IP/api/ha-probe/writes"
export HA_PROM_RW_URL="http://$OBS_IP:9090/api/v1/write"
```

LB 규칙 이름을 모르면 터미널 B에서 `gcloud compute forwarding-rules list --format='table(name,region.basename(),loadBalancingScheme)'`로 후보를 찾고, 정확한 규칙을 `gcloud compute forwarding-rules describe '<규칙 이름>' --region='<리전>' --format='value(IPAddress)'`로 **본인 화면에서만** 읽는다. 5초 스모크는 **별도 새 ID**, 1분 기준선도 **또 다른 새 ID**가 필요하다. 두 실행은 같은 단기 ADMIN JWT를 쓰되 결과 원장은 섞지 않는다. 스모크가 201·drop 0·원격 Counter 일치를 보여주지 않으면 1분 실행을 시작하지 않는다.

```bash
SMOKE_RUN_ID="ha$(date -u +%m%d%H%M%S)"
test "$SMOKE_RUN_ID" != "$RUN_ID"
HA_PROM_RW_URL="$HA_PROM_RW_URL" bash "$SCRIPT_DIR/run_ha_probe_k6.sh" \
  "$SMOKE_RUN_ID" 1 5s "$LB_URL" "$TOKEN_FILE" baseline-write 5
```

스모크 종료 코드 0, `k6-summary.json`의 201 5건·미전송 0, `원격-지표-대조.json`의 `final_totals_match=true`를 읽고 **통과한 경우에만** 다음 블록을 실행한다. 5건은 이 환경의 실제 관측값이며 다른 실행은 경계 시각 때문에 건수가 약간 달라질 수 있다.

```bash
HA_PROM_RW_URL="$HA_PROM_RW_URL" bash "$SCRIPT_DIR/run_ha_probe_k6.sh" \
  "$RUN_ID" 30 60s "$LB_URL" "$TOKEN_FILE" baseline-write 80
python3 -m json.tool "$HOME/ha389-runs/$RUN_ID/k6-summary.json"
python3 "$SCRIPT_DIR/sanitize_ha_k6_events.py" \
  --source "$HOME/ha389-runs/$RUN_ID/k6-events.jsonl" \
  --output "$HOME/ha389-runs/$RUN_ID/k6-events-safe.jsonl" --run-id "$RUN_ID"
python3 -m json.tool "$HOME/ha389-runs/$RUN_ID/원격-지표-대조.json" \
  | grep -E 'final_totals_match|query_attempts'
```

[실행 래퍼](https://github.com/DocGrid/docgrid/blob/765a87bb33e90b87ebfa4f04dbfe49003addf595/scripts/opensql/run_ha_probe_k6.sh)는 실행별 0700 폴더에 원장·요약·1초 수치를 구분해 쓴다. 종료 출력은 이번에 `run_id=ha449run0001 k6_exit=0 events=3602`였다. `final_totals_match=true`도 확인했다. 이는 **최종 Counter** 대조이며 Remote Write 중간의 매 전송 성공을 보장하지 않는다.

### 브라우저 — 실행 중·종료 직후

Grafana에서 시간 범위 **Last 5 minutes**, 새로고침 **5s**, 실행 ID를 **이번 ID**로 고른다. 새 ID는 k6 Remote Write가 시작된 뒤에 생긴다. 안 보이면 대시보드를 Refresh하거나 시간 범위를 다시 적용한다. **All은 모든 최근 실행을 합산한다.** 오늘 All은 스모크 5건 + 기준선 1,801건으로 2xx **1,806**이었고, `ha449run0001`을 선택하면 **1,801**이었다.

### 터미널 B — 현재 primary DB와 ID별 대조, 그다음 무조건 정리

DB exporter를 **현재** primary에 실행한다. 장애 후라면 primary가 달라질 수 있으므로 역할을 다시 확인한다. 파일에는 합성 request ID와 행 수만 남는다.

```bash
umask 077
EVIDENCE_DIR="$(mktemp -d)"
gcloud compute ssh "$PRIMARY_VM" --zone="$ZONE" --tunnel-through-iap \
  --ssh-key-file="$ACCESS_DIR/key" \
  --command="sudo -n /usr/local/sbin/docgrid-ha-probe-export '$PRIMARY_VM' '$RUN_ID'" \
  > "$EVIDENCE_DIR/db-counts.csv"
wc -l "$EVIDENCE_DIR/db-counts.csv"
```

오늘 기준선은 **CSV 1,802줄 = 헤더 1 + ID 1,801개**였다. 부하 VM에서 **sanitize를 통과한** `k6-events-safe.jsonl`과 `k6-summary.json`만 암호화 SCP로 비공개 분석 폴더에 가져온다. 원본 metrics JSONL·stderr·JWT를 복사하지 않는다.

```bash
REMOTE_RUN_DIR='<터미널 C에서 확인한 이번 실행 폴더>'
gcloud compute scp "$LOAD_VM:$REMOTE_RUN_DIR/k6-events-safe.jsonl" \
  "$EVIDENCE_DIR/safe-events.jsonl" --zone="$ZONE" --tunnel-through-iap \
  --ssh-key-file="$ACCESS_DIR/key"
gcloud compute scp "$LOAD_VM:$REMOTE_RUN_DIR/k6-summary.json" \
  "$EVIDENCE_DIR/k6-summary.json" --zone="$ZONE" --tunnel-through-iap \
  --ssh-key-file="$ACCESS_DIR/key"
```

[비식별 설정 예시](evidence/issue-448/ha449run0001/config-safe.json)를 `$EVIDENCE_DIR/config-safe.json`에 복사해 **새 ID·속도·기간**으로 고친다. 내부 주소·토큰은 넣지 않는다. 그다음 [기존 완료 실행 가져오기 도구](https://github.com/DocGrid/docgrid/blob/765a87bb33e90b87ebfa4f04dbfe49003addf595/scripts/opensql/import_completed_ha_k6.py)로 개별 ID를 비교한다.

```bash
python3 scripts/opensql/import_completed_ha_k6.py \
  --events "$EVIDENCE_DIR/safe-events.jsonl" \
  --k6-summary "$EVIDENCE_DIR/k6-summary.json" \
  --db-csv "$EVIDENCE_DIR/db-counts.csv" \
  --config-file "$EVIDENCE_DIR/config-safe.json" \
  --output "$EVIDENCE_DIR/imported" --run-id "$RUN_ID" --scenario baseline-write \
  --opensql-version not-rechecked --openproxy-version not-rechecked \
  --patroni-version not-rechecked --etcd-version not-rechecked
jq '{normal_baseline_pass,acknowledged_count,acknowledged_missing_count,db_duplicate_request_count,orphan_db_request_count}' \
  "$EVIDENCE_DIR/imported/reconciliation.json"
```

`normal_baseline_pass=true`이고 201 수 = DB 고유 ID 수, 누락·중복·고아 ID가 모두 0이어야 한다. 실패나 결과 불명은 201과 섞어 “성공”으로 바꾸지 않는다.

**시험 성공·실패와 관계없이 즉시 정리한다.** 오늘은 계정 4→0명, 세 VM의 타이머 3→0개를 확인했다. 정리 실패 시 임시 키를 먼저 지우지 말고 남은 계정·타이머를 조사한다.

```bash
python3 scripts/opensql/ha_fixture_cleanup.py cleanup "$RUN_ID" --guard-seconds 1200
python3 scripts/opensql/ha_fixture_cleanup.py status "$RUN_ID" --guard-seconds 1200
gcloud compute ssh "$LOAD_VM" --zone="$ZONE" --tunnel-through-iap \
  --ssh-key-file="$ACCESS_DIR/key" \
  --command="rm -f /tmp/$RUN_ID.jwt; test ! -e /tmp/$RUN_ID.jwt"
gcloud compute os-login ssh-keys remove --key-file="$ACCESS_DIR/key.pub" --quiet
rm -f "$TOKEN_DIR/jwt" "$ACCESS_DIR/key" "$ACCESS_DIR/key.pub"
rmdir "$TOKEN_DIR" "$ACCESS_DIR"
```

지우는 것은 **이번 실행의 임시 토큰·키뿐**이다. VM·LB·다른 실행의 데이터와 로그는 지우지 않는다.

## 4. Grafana에서 무엇이 어떻게 보여야 하나

그림 4. 이번 **정상 실행에서 실제 관찰한** 시간 흐름이다.

```text
16:50:20 KST     16:50:29 k6 시작       약 60초간 쓰기      16:51:31 종료
     │                 │                        │                   │
DB 역할          node3 PRIMARY ───────────── 유지 ─────────────── node3
timeline               14 ───────────────── 유지 ─────────────── 14
proxy TCP             2/2 ───────────────── 유지 ─────────────── 2/2
앱 지표 수집          A/B 성공 ───────────── 유지 ─────────────── 성공
HTTP 201/초           선 등장 → 약 30 req/s 근처 → 종료 후 감소
2xx 누적 카드          0 → 계속 증가 ───────────────────────▶ 1,801
3xx·4xx·5xx            0 ─────────────────────────────────▶ 0
활성 VU                부하 중 시계열 표시, 최대 1 → 종료 후 종료
Hikari 대기·timeout    0선
앱 5xx/초              0선
DB ID 대조             시험 종료 뒤 별도 수행 ────────────▶ 1,801개 각 1행
```

| 패널 | 오늘 본 값 | 왜 바뀌거나 그대로인가 · 한계 |
| --- | --- | --- |
| 앱 A/B 수집 | 두 행 모두 성공 | Prometheus `up`은 관리 지표 수집 성공. 모든 HTTP 쓰기 성공의 증거는 아님 |
| DB 역할·primary | node1/2 REPLICA, node3 PRIMARY | Patroni 역할 지표. 이번엔 장애가 없어 유지 |
| timeline | 14 | 현재 primary의 복구 이력. 역할 승격이 없어서 유지 |
| OpenProxy 포트 | 2/2 연결 가능 | 관측 VM의 TCP probe. 앱의 SQL 성공을 뜻하지 않음 |
| HTTP 201/초 | 실행 중 선이 나타나 약 30 req/s | 완료된 201 Counter의 최근 30초 비율이라 처음·끝에서 완만하게 변함 |
| 2xx·3xx·4xx·5xx | 1,801·0·0·0 | 실행 ID별 응답 대역. 이번 2xx는 모두 201이지만 항상 같은 뜻은 아님 |
| 활성 k6 VU | 실행 중 표시, 1초 표본 최대 1 | 진행 중인 반복 수. 순간 최대를 완전 포착한 값은 아니며, 종료 후 시계열이 사라져도 기록된 수치는 남음 |
| Hikari 대기·timeout | 0선 | DB 연결 대기·timeout. DB 무결성의 단독 증거는 아님 |
| 앱별 HTTP 5xx | 0선 | 앱이 기록한 오류. LB 자체 응답은 여기서 빠질 수 있어 k6 결과와 함께 봄 |
| standby WAL 재생 대기 | 거의 0선 | 이미 받은 WAL의 재생 대기량. 전체 복제 지연이나 RPO 0 보장은 아님 |

그림 5. 다음 **장애 영상에서 해석할 예상 흐름**이다. 아래는 이번에 실행한 결과가 아닌 설명용 예시다.

```text
[예시: replica 쪽 OpenProxy A만 중단]
장애 표식 → proxy-A “연결 불가” → 앱이 생존 proxy-B로 새 연결
          → k6 5xx·결과 불명·201/초 변화 확인
복구 표식 → proxy-A “연결 가능” → k6 201/초 안정화
※ TCP가 복구돼도 SQL·DB 행 대조를 따로 해야 한다.

[예시: primary VM 전체 상실]
장애 표식 → 그 DB 행은 지표 수집 실패, 동거 proxy 행도 TCP 실패
          → Patroni 새 primary 선출, timeline 변화 가능
          → 그 사이 k6 5xx·결과 불명·201 공백 확인
복구 뒤  → 201 재개, 옛 노드 replica 재합류, 새 primary DB에 ID별 대조
※ 이번 리허설에서는 이런 장애를 주입하지 않았다.
```

Grafana는 **언제 어떤 지표가 움직였는지**, 외부 원장과 DB CSV는 **201을 받은 ID가 실제 1행인지**를 말한다. `up=0`만으로 VM 사망이라고 단정하지 않고, proxy TCP 연결 성공만으로 SQL 성공이라고 말하지 않는다. k6 timeout 결과는 요청 시작보다 늦게 그래프에 찍힐 수 있다.

## 5. 이번에 실제 실행한 것과 결과

| 위치 | 실행한 명령·방법 | 실제 결과 | 판정 |
| --- | --- | --- | --- |
| Mac 제어 | GCP VM 목록·내부 LB backend health | 관련 VM 8대 RUNNING, LB 2/2 HEALTHY | 환경 확인; 쓰기 성공의 증거는 아님 |
| Mac 제어 | `ha_fixture_cleanup.py prepare ha449run0001 --guard-seconds 1200` | 계정 4명·독립 타이머 3/3 | 준비 통과 |
| GCP 부하 VM | `run_ha_probe_k6.sh ha449smk0001 1 5s … baseline-write 5` | 5건 201, drop 0, k6 종료 0 | 연결 스모크 통과 |
| GCP 부하 VM | `run_ha_probe_k6.sh ha449run0001 30 60s … baseline-write 80` | 1,801건 201, 실패 0, drop 0, p95 53.42ms, 종료 0 | 정상 1분 기준선 통과 |
| GCP 부하 VM | 원장 sanitize·Remote Write 대조 | 안전 이벤트 3,602건, 원장=Prometheus 1,801, 첫 조회 일치 | 최종 Counter 일치; 중간 전송 전부는 미확인 |
| 현재 primary·Mac | DB export·완료 실행 ID별 대조 | DB 1,801 ID·1,801행, 누락·중복·고아 0 | 정합성 통과 |
| Grafana | 실행 ID와 16:50:20~16:51:50 KST 고정 조회 | 카드 1,801·0·0·0, 201/초·VU 선 | 화면 연결 확인 |
| Mac·GCP | 계정·타이머·JWT·임시 키 제거 | 잔여 계정 0, 활성 타이머 0/3 | 원복 확인; VM은 유지 |

## 6. 오류가 나면 중단 지점

| 증상 | 먼저 볼 것 | 잘못된 결론 |
| --- | --- | --- |
| 401·403 | JWT 만료·ADMIN 역할·앱 설정 | DB 장애라고 부르지 않음 |
| dropped iterations > 0 | VU·부하 VM CPU·요청 대기 | 목표 부하를 전부 발생했다고 하지 않음 |
| k6 201인데 Grafana No data | 실행 ID·시간 범위·Remote Write 대조 | DB 쓰기 실패라고 단정하지 않음 |
| 카드와 원장 불일치 | All 합산·10분 lookback·원격 최종 합계 | 합산 카드를 한 실행 결과로 쓰지 않음 |
| DB 0행 또는 2행 이상 | 현재 primary·같은 run ID인지 재확인 | 정상 통과·RPO 0을 선언하지 않음 |
| 정리 상태가 0이 아님 | 세 타이머·현재 primary 계정 재조회 | 정리 완료라고 보고하지 않음 |

이번 준비 중에도 **초기 SSH 공개키 인증이 실패**했다. 그래서 기존 키를 추측하거나 보호된 파일을 뒤지는 대신, 만료 시간이 1시간인 새 OS Login 키를 등록해 접속했고 종료 후 제거했다. 또 인증 없이 내부 LB에 보낸 사전 POST는 **401**이었다. 이것은 API 보안 경계가 응답한 것이지 정상 201 쓰기 성공이 아니다. 정상 부하는 합성 ADMIN JWT를 준비한 뒤에야 수행했다. Grafana의 과거 구간을 URL의 ISO 시각으로 직접 넣은 첫 시도는 1970년으로 잘못 해석됐다. 화면의 시간 선택기를 쓰거나 Unix 밀리초 값을 쓰면 정상 구간이 표시된다. 이 준비 단계의 실패·재시도는 5초·1분 k6 실행 결과에 섞지 않았다.

## 7. 아직 시험하지 않은 것

이번에는 PostgreSQL 프로세스 종료, primary VM 상실, OpenProxy 중단을 **실행하지 않았다**. 장애 시 오류율·RTO·RPO는 이번 문서의 신규 결과가 아니다. Node1/2의 전체 DB 행 대조, etcd 정족수, 앱 A/B 배포 JAR 버전도 이번 실행에서는 재측정하지 않았다. 다음 장애 시연은 최신 백업·독립 복구 가드·현재 역할을 다시 확인하고 **목적마다 별도 실행 ID**로 해야 한다.

이 문서와 안전 증거는 PR #449의 정상 리허설 결과로 관리한다. 원본 압축 파일은 사전 허용 필드 검사와 사후 민감 패턴 검사를 통과했다.
