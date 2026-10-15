# #503 README 앞부분에 시연 영상과 검증된 범위 표 추가

closes #503

---

## 배경

- 2차 평가(기능테스트·라이선스 검증, 10/12~10/28)는 저장소 상태를 기준으로 진행되고, 11/4에는 발표가 있다.
- `docs/test-results`에는 OpenSQL 3노드 장애, 권한 회수 복제 지연, 하이브리드 검색 품질, DB·GCS 시점 복원, 인덱싱 성능의 실행별 기록이 있다. 그러나 README 앞부분의 「관측 구성과 검증 범위」는 실행 기록 3개만 링크한다.
- 「OpenSQL 핵심 부하·장애 검증」 표는 「테스트」 절 뒤에 있어 처음 화면에서 보이지 않았다. 검색 품질·시점 복원·성능 결과는 README에 없었다.
- 시연 영상 4개를 촬영했지만 README에서 찾을 수 없다.

---

## 결정

**README 앞부분에 「시연 영상」과 「검증된 범위」 두 절을 둔다. 아래쪽 「OpenSQL 핵심 부하·장애 검증」 절은 「검증된 범위」로 옮기고 삭제한다.** 같은 결과가 README 두 곳에 있으면 수치가 따로 바뀔 수 있으므로 한 곳에만 둔다.

| 절 | 위치 | 내용 |
|---|---|---|
| 시연 영상 | 소개 문단 바로 다음, 「주요 기능」 앞 | YouTube 썸네일 2×2 표. 썸네일을 누르면 영상으로 이동 |
| 검증된 범위 | 「주요 기능」 다음, 「아키텍처」 앞 | 「OpenSQL 3노드 부하·장애」 표 9행, 「검색 품질·복원·성능」 표 3행 |

### 시연 영상

영상 제목은 YouTube oEmbed(`https://www.youtube.com/oembed?url=...`)로 조회한 실제 제목에서 대회명 접두어만 뺐다. 영상 내용을 따로 요약하지 않았다. 영상에 나온 실행이 표의 어느 실행 ID인지 확인하지 않았으므로 표와 연결하지 않는다.

| 순서 | 제목 (YouTube 원문) | 주소 |
|---|---|---|
| 1 | 서비스 사용 흐름 및 인덱싱 장애 대응 시연 | https://youtu.be/t2JHrvTSC-w |
| 2 | OpenProxy A 장애 중 k6 부하·HTTP 응답 시연 | https://youtu.be/hwP4-J86BqE |
| 3 | OpenProxy A 장애 중 실제 웹 기능 시연 | https://youtu.be/i9-LmozKqu4 |
| 4 | Primary VM 장애·Failover·멱등 재전송 | https://youtu.be/NWlwwDB0a94 |

썸네일은 `https://img.youtube.com/vi/{id}/mqdefault.jpg`(16:9, 레터박스 없음)를 쓴다. 2026-10-15에 4개 모두 HTTP 200을 확인했다.

### 표 구조

두 표 모두 「시험 | 실행 목적 | 실행 조건 | 관측 결과 | 실행 기록」 5열이다.

- **실행 목적**: 왜 이 시험을 했는지 한 문장으로 적는다. 각 결과 문서의 「왜 이 시험을 했나」·결론, 설계 문서의 배경, 시험 이슈의 「목적」에서만 가져온다.
- **실행 조건**: 부하, 실행 ID나 횟수, 날짜를 적는다.
- **관측 결과**: 수치를 원문 그대로 옮긴다. 장애 순간의 HTTP 500·503과 결과 불명 건수도 숫자로 남기되, "무중단 시험은 실패" 같은 판정 문구 대신 "장애 순간 HTTP 500 A 3건·B 8건"처럼 관측 사실로 적는다.
- 표 아래 문단에 판정 방식(요청 원장 `request_id` ↔ 최종 primary DB 행 대조)과 해석 범위(무중단 가용성·모든 장애의 RPO 0을 주장하지 않음)를 적는다.

### OpenSQL 3노드 부하·장애 (9행)

기존 「OpenSQL 핵심 부하·장애 검증」 7행에 README에 없던 2행(부하 중 계획 switchover, etcd 두 멤버 중단)을 더했다. 기존 행의 수치는 바꾸지 않았다. OpenProxy 행에는 재실행의 201 건수, 각 행에는 실행 ID와 날짜를 더했다.

| 행 | 실행 목적의 출처 | 수치를 확인한 원문 위치 |
|---|---|---|
| 정상 쓰기 부하 | `gimin-389-…-20261002.md` 3행 "이후 OpenProxy/primary 장애를 같은 잣대로 비교하기 위한 정상 상태 기준선" | 같은 문서 7~9행 |
| OpenProxy 한쪽 장애 | `gimin-391-…-20261003.md` 7행 「왜 이 시험을 했나」 | 172~173행 `ha391fa20001`·`ha391fb20001`. 실행 시각은 `evidence/issue-391/ha391fa20001/실행-기록.md` 6행(2026-10-03 KST) |
| Patroni 계획 역할 전환 | `gimin-401-…-20261003.md` 10행 「문제와 원인 가설」의 `Replica:stopping` | 같은 문서 4~6행, 42~43행 streaming 복귀 19초·18초 |
| 부하 중 계획 switchover | `issue-412-…-20261004.md` 10행 「왜 다시 시험했나」 | 5행 실행 시각, 64행 `ha412sw400c`, 93행 최대 201 공백 6.204초 |
| primary 프로세스 장애, 리더 VM 즉시 상실 | 이슈 #420 「목적」 "계획된 역할 이전과 구분해, 부하 중 비계획 OpenSQL primary 장애 두 유형의 쓰기 회복과 데이터 보존을 측정" | `opensql-primary-unplanned-fault-load-20261004.md` 3행 실행일, 7~10행, 52~53행 |
| etcd 두 멤버 중단 | 이슈 #428 「목적」 | `opensql-etcd-http-failsafe-20261004.md` 5·7행, 49행 `e428htwo01` |
| DB 장애 중 인덱싱 복구 | 이슈 #424 「목적」 | `opensql-worker-primary-fault-20261004.md` 5행, 90행 `ha424fault003`, 93행 약 5분 43초(기본 lease 5분 포함) |
| 복제 지연·권한 안전성 | `opensql-permission-routing-preflight-20260929.md` 판정(권한 SQL이 standby에 도착) | 재현 `opensql-permission-replica-lag-20260929.md` 결론, 수정 후 `opensql-admin-role-primary-consistency-20261001.md` 5행·27행 |

### 검색 품질·복원·성능 (3행)

| 행 | 실행 목적의 출처 | 수치를 확인한 원문 위치 |
|---|---|---|
| 하이브리드 검색 품질 | `docs/design/kangcheolung-#456-hybrid-search.md` 「1.1 확인한 문제」 ① | `kangcheolung-#456-hybrid-search.md` 89~92행. 세트 1~3의 1위 정답 수·MRR@10은 `evidence/issue-456/real-*.json`으로 다시 계산해 일치를 확인 |
| DB·GCS 시점 복원 | `docs/design/db-gcs-point-in-time-restore.md` 「목표와 현재 관문」 | `db-gcs-point-in-time-recovery-20261015/README.md` 5~7행. 후속 서비스 경로는 `db-gcs-service-recovery-20261015/README.md` 5행 |
| PDF·DOCX 인덱싱 E2E | `docs/design/gimin-#143-pdf-docx-indexing-e2e-load-benchmark.md` 「1. 배경」 | `gimin-#145-final-performance-report.md` 38행 조건, 153~161행 결과. CPU BGE-M3·Worker 슬롯·장비·날짜는 `gimin-#143-pdf-docx-indexing-e2e-load-benchmark.md` 28·31·40·41행 |

하이브리드 검색 문서에는 측정 날짜가 적혀 있지 않다. 그래서 날짜 대신 **증빙 커밋 날짜**(`1c79d1e`, 2026-10-10)를 적었다.

### 넣지 않은 항목과 이유

| 항목 | 이유 |
|---|---|
| 동기 standby 링크 차단 (`sync433fault01`, `ha435fault01`) | 기존 절도 표 밖에서 통합 보고서로 안내했다. 같은 방식으로 표 아래 문단에서 안내한다 |
| Worker 수평 확장 (`gimin-#138`) | TXT 6,400자 부하라 PDF·DOCX E2E와 조건이 다르다. 한 표에 같이 두면 비교로 읽힐 수 있다. 성능 행의 링크(`#145`)에서 이어서 볼 수 있다 |
| 복원본 서비스 경로 (`db-gcs-service-recovery-20261015`) | 실제 BGE-M3·Ollama 없이 고정 벡터 스텁으로 일부 경로만 확인했다. 수치를 따로 행으로 올리지 않고 시점 복원 행에서 링크하며, 표 아래에 스텁 사용을 적는다 |
| 시연 영상 4의 멱등 재전송 | 저장소의 런북 2개(`opensql-idempotent-*-20261010.md`)는 "GCP 장애·부하 미실행" 상태이고 GCP 실행 결과 기록이 없다 |

### 작성 규칙

- 조건이 다른 수치를 합산하거나 비교하지 않는다. 하이브리드 검색의 111문항 합계는 원문 표의 합계 행을 그대로 옮긴 값이다.
- 원문에 없는 해석은 덧붙이지 않는다. 22ms·105ms 같은 시간 값은 원문이 RTO로 해석하지 말라고 적어 두었으므로 표에 넣지 않았다.
- 통합 보고서 159행에 따라 "장애를 견디는 구성"과 "무중단·무손실 보장"을 한 문장에 쓰지 않는다.

---

## 변경 범위

| 파일 | 변경 |
|---|---|
| `README.md` | `## 시연 영상`, `## 검증된 범위`(하위 표 2개) 추가. 기존 `## OpenSQL 핵심 부하·장애 검증` 절은 내용을 옮긴 뒤 삭제 |
| `docs/design/gimin-#503-readme-verified-scope.md` | 이 설계 문서 |

**API·코드·설정 변경은 없다.** 따라서 API 명세와 에러 케이스는 해당 없다.

다른 작업(#502)이 README 「빠른 실행」(MinIO)과 라이선스 표를 수정하므로 두 절은 건드리지 않는다.

---

## 검증

- 표의 모든 수치를 링크된 원문과 대조한다.
- README의 상대 링크(`docs/test-results/...`)가 실제 파일·폴더를 가리키는지 스크립트로 확인한다.
- 삭제한 절의 앵커 `#opensql-핵심-부하장애-검증`을 참조하는 문서가 저장소에 없는지 확인한다.

---

## 범위 밖

- 새 장애·부하·복원 시험 실행
- 「관측 구성과 검증 범위」 링크 정리
- 영상과 실행 ID의 대응 확인
