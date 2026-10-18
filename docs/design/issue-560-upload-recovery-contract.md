# 업로드 성공 응답의 DB·GCS 복원 판정 계약

## 목적과 범위

업로드 201 응답을 받았다는 사실과, 복원 DB·원본에서 해당 업로드가 보존됐다는 사실을 분리한다. 기존 Barman/WAL·GCS 보존 도구를 다시 만들지 않고, 클라이언트 응답 원장과 단계별 관문을 추가한다. 관련 이슈는 #560이다.

이 변경은 로컬 Python 계약과 실행 제어기다. 실제 OpenSQL 복원, 실제 GCS 다운로드, BGE-M3 검색의 성공 증거는 후속 격리 실행에서 수집해야 한다. `PASS`는 입력한 연산자의 계약 충족을 뜻하며 RPO 0이나 공개 서비스 RTO를 보장하지 않는다.

## 재사용할 기존 도구

| 구성요소 | 재사용 범위 | 추가로 필요한 관측 |
|---|---|---|
| `barman_wal_follow_primary.py` | Primary 대상 선택과 수신 재연결 | 실제 수신 프로세스·새 WAL 진행; dry-run만으로 정상 수신 판정 금지 |
| `barman_offsite_sync.py` | DONE 기준 백업과 완결 WAL 외부 전송 | 복원 가능한 WAL 연결·외부 identity; 마지막 전송 성공만으로 판정 금지 |
| `verify_gcs_original_preservation.py` | 장애 전 객체 복사·generation·해시 원장 | 장애 뒤에는 원본 접근 없이 고정 보존본만 읽어야 함 |
| `ha_evidence.py` | 0600 쓰기·fsync·원자적 파일 교체 | 이번 실험 전용 요청 ID·응답 ID·최초 감사 스키마 |
| `ha_worker_documents.py` / `analyze_worker_recovery.py` | 합성 문서와 최종 Job·Chunk·Embedding 대조 | 업로드 응답 ID 4종과 최초 복원 상태를 별도 보존 |

```text
그림 1 — 장애 이전 응답 원장과 고정 입력 [실행 설계]

시험 클라이언트 → record_sent(request_id, SHA-256, 크기, cohort)
                 → 실제 업로드 API 호출
                 → record_response(HTTP 상태, 문서·버전·파일·Job ID)
                    ├─ 200/201 + ID 4종: ACKNOWLEDGED
                    ├─ 응답 미수신: UNKNOWN
                    └─ 다른 HTTP 상태: HTTP_ERROR (DB 롤백을 뜻하지 않음)

백업 연산자 → 기준 백업·완결 WAL·원본 보존본 준비
           → identity 해시·객체 generation·SHA-256 고정
시험 제어기 → validate_inputs: 준비 조건 7종 모두 참인지 확인
           → freeze_inputs: inputs.json 단독 생성 + cutoff 이벤트
           → 이후 새 요청 등록 금지, 입력 해시 변경 시 실행 금지
```

응답 원장은 DB 밖에 두며 전송 시각과 응답 수신 시각은 해당 이벤트를 기록한 시계 기준이다. cutoff는 제어기가 입력을 고정한 시각이다. 실제 장애 발생·원본 접근 차단 시각과 같다고 추정하면 안 된다. 실제 시험은 두 시각과 차이를 별도 기록한다.

## 복원 판정과 최초 감사

`dr_recovery.py`는 신규 실행 디렉터리만 만들고, 해시 연결 JSONL 원장에 순서·실행 ID·UTC 시각을 기록한다. 이는 누락·변경 검출을 위한 체크이며, 원장을 재작성할 수 있는 공격자에 대한 전자서명은 아니다.

```text
그림 2 — _execute 내부의 변경 전 관문 [구현]

execute.lock 획득 → 여섯 연산자·timeout·정리 명령 전체 검증
    │
    ▼
restore → 고정 입력 해시 일치 + source_access_used=false 확인
    │
    ▼
snapshot → Worker/Flyway/수선/원본 접근이 모두 false인지 검사
    │       → 요청마다 DB·버전·파일·Job ID 및 관계 대조
    │       → 보존 객체의 generation·실제 SHA-256·크기 대조
    ▼
initial-snapshot.json + initial-audit.json 단독 생성·fsync
    │
    ├─ 기능 코호트 누락 / 부분 DB / 원본 누락 / 해시 불일치
    │      → activate 호출 금지 → cleanup
    └─ 기능 코호트 복원 + 손실 코호트의 DB 누락만 허용
           → activate → 격리 DB 버킷 재지정·Flyway·Worker 시작 허용
```

`DB_MISSING`은 실제 조회가 누락을 확인한 경우에만 기록한다. 관측 행 자체가 없으면 증거 부족으로 중단한다. 최초 감사에 손실이 있더라도 후속 수선 결과로 이를 덮어쓰지 않는다. `generation`은 원본 버킷의 과거 숫자와 비교하는 것이 아니라 **동결한 보존 객체의 버전**과 비교한다. 별도 버킷 복사 시 새 generation을 동결 원장에 기록해야 한다.

## 기능별 시간과 정리

```text
그림 3 — 최초 감사 이후와 종료 [구현·실행 설계]

activate 완료 시각
    → functional 연산자
       ├─ download: 기존 원본 다운로드
       ├─ search: 기존 문서 검색
       ├─ job_resume: 중단 Job 재개
       ├─ new_upload: 새 업로드
       ├─ new_indexing: 새 문서 INDEXED
       └─ new_download: 새 원본 다운로드
          각 항목: 통과 여부·표본 수·관측 UTC·cutoff 이후 초
    → final_snapshot
       → 미완료 Job·중복 Chunk·중복 Embedding·누락 Embedding 모두 0
       → final-audit 별도 생성 (initial-audit 보존)
    → finally cleanup: 공개 설정 보존·원본 클러스터 복귀·소유 자원 정리
       ├─ 정리 확인 + 필수 단계 완료: PASS
       └─ 단계 실패·중단·정리 미확인: INCOMPLETE
```

단계별 연산자 명령은 비공개 JSON의 argv 배열로만 전달한다. stdout JSON은 메모리에서 검증하며, stderr·명령문·SSH 주소·자격 증명을 증거에 복사하지 않는다. 스냅샷은 필드와 값 형식을 허용 목록으로 검사한 후 저장한다. 제어기는 연산자의 진술이 실제 환경과 같은지 독립적으로 증명하지 않는다. 후속 실행에서 DB SQL·보존 객체 읽기·HTTP 결과와 함께 근거를 남겨야 한다.

## 사용 절차와 실행 전 조건

```bash
python3 scripts/opensql/dr_recovery.py init --run-dir "$RUN_DIR" --run-id "$RUN_ID" --revision "$REVISION"
python3 scripts/opensql/dr_recovery.py sent --run-dir "$RUN_DIR" --input "$SENT_JSON"
python3 scripts/opensql/dr_recovery.py response --run-dir "$RUN_DIR" --input "$RESPONSE_JSON"
python3 scripts/opensql/dr_recovery.py preflight --input "$INPUT_MANIFEST"
python3 scripts/opensql/dr_recovery.py freeze --run-dir "$RUN_DIR" --input "$INPUT_MANIFEST"
python3 scripts/opensql/dr_recovery.py execute --run-dir "$RUN_DIR" --input "$PRIVATE_OPERATOR_PLAN"
```

`RUN_DIR`는 존재하지 않는 새 디렉터리이며 부모는 준비되어 있어야 한다. 실제 실행 계획은 `restore`, `snapshot`, `activate`, `functional`, `final_snapshot`, `cleanup`을 모두 포함하고, 각 항목에 `argv`와 최대 1,800초의 `timeout_seconds`를 둔다. private plan 자체는 커밋하지 않는다. 같은 실행을 재시도하지 말고 새로운 run ID·PGDATA·원장으로 시작한다.

준비 조건은 기준 백업 확인, WAL 연결 확인, 실제 수신 정상, 외부 identity 확보, 복원 대상 격리, 원복 준비, 공개 설정 보존이다. 모두 참인 boolean 입력은 실제 관측을 대신하지 않는다. 각 조건의 실제 근거가 없으면 false로 남기고 NO-GO를 기록한다. 이번 변경은 서비스 환경 파일·공개 버킷·운영 DB 정책을 바꾸지 않는다.
