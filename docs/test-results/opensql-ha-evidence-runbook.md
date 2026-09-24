# OpenSQL 3노드 장애 실험 증거 기록 가이드

## 목적과 판정 경계

장애 실험의 실행 조건과 외부 요청 결과를 **DB 밖**에 남기는 기록기다. 이 문서는 실험 재현
절차이며 실제 GCP 장애 전환 성공 보고서가 아니다. 장애 주입, HTTP 요청, 제품 버전 조회는 기록기가
대신하지 않는다. 실험 실행기가 확인한 사실만 입력하고 원본 `events.jsonl`을 보존한다.

| 파일 | 기록 내용 | 판정에 쓰는 이유 |
| --- | --- | --- |
| `manifest.json` | `run_id`, UTC 시작 시각, Git SHA, OpenSQL·OpenProxy·Patroni·etcd 버전, 비밀 제거 설정의 SHA-256 | 실행 조건 고정 |
| `events.jsonl` | 요청 시작·응답·결과 불명, 장애 시작·종료, 실험 종료 시각 | DB 장애와 무관한 원본 타임라인 |
| `requests.csv` | 요청별 ID, 작업 이름, 송신·완료 시각, HTTP 상태, 결과 | 성공/실패/불명 건수 교차검증 |
| `summary.json` | 원본을 재생한 집계, 열린 장애, 실행 완료 여부 | 보고서 숫자 검증 |

`ACKNOWLEDGED`는 애플리케이션에서 **2xx HTTP 응답을 실제로 받은 요청**이다. `FAILED`는
3xx~5xx 응답을 실제로 받은 요청이다. 응답이 없거나 타임아웃·클라이언트 중단으로 결과를 모르면
`UNKNOWN`이다. `sent`만 남고 종결 이벤트가 없는 요청도 자동으로 `UNKNOWN`이 된다. 5xx를
받았더라도 DB 트랜잭션이 커밋되지 않았다고 단정하지 않는다. 성공 응답의 DB 내구성·중복 여부는
다음 장애 실험에서 동일 ID를 DB와 대조해 별도 판정해야 한다.

## 실행 전 준비

Python 3.9 이상과 저장소 Checkout이 필요하다. 실제 버전은 각 구성 요소에서 조회한 값을 기록한다.
설정 지문은 비밀번호·토큰·내부 IP 등을 제거한 설정 스냅샷에서 계산한다. 원본 설정 파일이나
자격증명을 이 기록기에 넘기지 않는다. 결과 디렉터리는 Git 저장소 밖의 접근 제한된 경로로 지정하고,
공개 PR에는 실제 원본 로그를 자동으로 첨부하지 않는다.

```bash
python3 scripts/opensql/ha_evidence.py init \
  --run-dir /private/tmp/opensql-ha-run-example \
  --scenario proxy-process-stop \
  --config-sha256 <redacted-config-sha256> \
  --opensql-version <observed-version> \
  --openproxy-version <observed-version> \
  --patroni-version <observed-version> \
  --etcd-version <observed-version>
```

`init`은 새 디렉터리에만 성공하며 생성한 `run_id`를 출력한다. 파일은 소유자 전용 권한으로
기록된다. 동일 조건 반복 실험도 각각 새 디렉터리와 `run_id`를 사용한다.

## 요청과 장애 기록

1. **실제 HTTP 요청을 보내기 직전** `sent`를 기록하고 출력된 ID를 실험 실행기의 요청 헤더나
   본문 ID와 연결한다. 기록기 자체는 네트워크 요청을 보내지 않는다.
2. 실제 2xx 응답을 받은 경우에만 `ack`, 실제 3xx~5xx 응답을 받은 경우에만 `fail`을 기록한다.
   타임아웃·연결 단절 등 응답이 확인되지 않은 경우 `unknown`을 기록하거나 `sent`만 남긴다.
3. 장애 주입 명령 실행 직전·종료 직후에 각각 `fault start`·`fault end`를 기록한다. 이름은
   동일하게 유지하되 계정, 내부 IP, 비밀값을 포함하지 않는다.
4. 실험이 끝나면 `finish`가 종료 시각을 기록하고 JSON·CSV를 생성한다. 비정상 종료 시에는
   `export`로 미완료 상태의 스냅샷을 생성한다. `verify`는 원본과 파생 파일의 일치 여부를 검사한다.

```bash
python3 scripts/opensql/ha_evidence.py sent \
  --run-dir /private/tmp/opensql-ha-run-example --operation document-write
# 출력된 request_id로 실제 HTTP 요청을 호출·추적한다.
python3 scripts/opensql/ha_evidence.py ack \
  --run-dir /private/tmp/opensql-ha-run-example --request-id <request-id> --http-status 201
python3 scripts/opensql/ha_evidence.py fault \
  --run-dir /private/tmp/opensql-ha-run-example --name proxy-a-stop --phase start
# 별도 실험 실행기가 프록시 프로세스를 중단·복구한다.
python3 scripts/opensql/ha_evidence.py fault \
  --run-dir /private/tmp/opensql-ha-run-example --name proxy-a-stop --phase end
python3 scripts/opensql/ha_evidence.py finish --run-dir /private/tmp/opensql-ha-run-example
python3 scripts/opensql/ha_evidence.py verify --run-dir /private/tmp/opensql-ha-run-example
```

미종결 요청 또는 열린 장애가 있는 채 `finish`하면 `complete=false` 또는 `UNKNOWN`이 표시된다.
`finish` 뒤 이벤트 추가와 동일 ID의 재종결은 거부한다. CSV와 집계 JSON은 원본 이벤트에서만
재생성한다. `verify` 불일치가 나면 원본을 고치지 말고 차이를 조사하며, 보고서에 임의 숫자를
복사하지 않는다.

이 검사는 형식 오류와 원본·파생 파일의 불일치를 찾는 장치이지 암호학적 위변조 방지 장치는
아니다. 누군가 유효한 JSONL 이벤트를 바꾸고 집계도 다시 만들면 `verify`만으로 식별할 수 없다.
심사 제출 증거는 별도로 읽기 전용 보관하거나 서명·체크섬을 외부 저장소에 남겨야 한다.

## 검증과 앞으로의 연결

```bash
python3 -m unittest scripts/opensql/test_ha_evidence.py -v
```

회귀 테스트는 세 결과의 분리, 응답 없이 끝난 요청, 장애 시각, 중복·잘못된 응답의 거부,
원본/집계 변조 감지, 미종료 장애를 검증한다. 이는 로컬 기록기 테스트이지 실제 OpenProxy,
Patroni, etcd 또는 DocGrid 애플리케이션의 장애 전환 검증이 아니다. 이후 프록시·리더 장애
실험에서는 부하 발생기의 요청 ID와 DB에 저장된 멱등 키를 대조하고, 장애 전후 요청 실패 수,
복구 시간, 성공 응답 후 누락 건수를 같은 `run_id`로 보고해야 한다.
