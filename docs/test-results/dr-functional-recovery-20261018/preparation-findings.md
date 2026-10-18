# 준비·재시도에서 확인된 사항

본시험 이전에 실제로 확인한 문제와 재시도를 구분한다. 준비가 통과했다는 사실을 기능 복원 통과나 RPO 측정으로 합치지 않는다. UTC 원본 시각은 `+00:00`, 한국 시각은 여기에 9시간을 더한다.

## 복원 판정기 상태 계약

| 회차 | 목적·실행 위치 | 관측 결과 | 해석·근거 |
|---|---|---|---|
| DR-STATUS-COMPAT-20261018-R1 | 로컬 Python, 실제 Java enum을 입력 계약으로 읽음 | 40개 메서드 중 4개 실패. assertion 3건·오류 7건 | 기존 공통 상태 목록이 실제 `INDEXING`·`EMBEDDING` 등 상태를 거부하고 다른 엔티티의 상태를 허용. [원본 판정](checks/status-compat-r1/result.json) |
| DR-STATUS-COMPAT-20261018-R2 | 엔티티별 허용 목록 적용 뒤 같은 계약 | 40/40 통과, 오류 0, 1.066초 | Java 상태 18종과 잘못된 엔티티 상태 3종을 독립 입력으로 확인. [결과](checks/status-compat-r2/result.json) |
| DR-CONTRACT-20261018-R5 | 본시험 전 최종 계약 회귀 | 40/40 통과, 1.014초 | `python3 -m unittest discover -s scripts/opensql -p test_dr_recovery.py -v`. 합성 입력·제어 자식 프로세스 시험이며 클라우드 복원 성공 증거는 아님. [로그](checks/contract-r5/events.log) |

## 첫 복원 리허설과 라이선스 경로

| 회차 | 실제 도달 단계 | 판정 | 원복 |
|---|---|---|---|
| DR-RESTORE-20261018-REHEARSAL1 | 외부 입력 import·검증·새 PGDATA 준비 뒤 OpenSQL 기동에서 중단 | **INCOMPLETE**. 최초 감사·기능 시험 미실행. 성공 응답 13건을 수집했지만 복원 손실 건수는 판정하지 못함 | 원래 3노드·공개 설정 복구. 만료된 SSH 키 제거의 초기 미확인을 후속 프로필 조회로 보완. [요약](runs/DR-RESTORE-20261018-REHEARSAL1/summary.json), [키 재확인](runs/DR-RESTORE-20261018-REHEARSAL1/ssh-cleanup-reconciliation-r2.json) |
| DR-PG-START-DIAG-20261018-R1 | 같은 외부 백업 바이트를 새 PGDATA에 복원하고 네이티브 오류 수집 | **INCOMPLETE**. `FATAL: license is invalid` 확인. 첫 리허설은 원시 stderr를 보존하지 않아 같은 메시지가 나왔다고 소급 단정하지 않음 | 공개 설정 보존·3노드 복귀·소유 자원 정리 모두 확인. [결과](runs/DR-PG-START-DIAG-20261018-R1/result.json) |
| DR-PG-START-DIAG-20261018-R2 | 같은 호스트·같은 백업/WAL 해시, 기존 프로세스의 `OPENSQL_LICENSE_PATH`만 런처 환경에 추가 | **기동 진단 PASS**. 실제 OpenSQL 쓰기 가능 확인. 본시험 사전 조건은 NO-GO이며 기능·RPO 통과 회차가 아님 | 세 정리 조건 모두 확인. [결과](runs/DR-PG-START-DIAG-20261018-R2/result.json) |

확인한 원인은 임시 복원 런처가 기존 설치의 라이선스 경로 환경을 전달하지 않은 것이다. 라이선스 내용·만료·제품 결함으로 결론내리지 않았다. 파일 내용을 읽거나 수정·재발급하지 않았다. 기존 정상 프로세스의 환경 경로를 같은 호스트에서 재사용했다. 새 팀 공통 `.env` 항목이나 공개 설정 변경은 없다.

진단용 외부 객체를 되살릴 때에는 GCS soft delete의 기존 generation을 제자리에서 사용한 것이 아니다. 복원 API가 만든 **새 generation**을 읽고 원래 동결 바이트의 SHA-256·크기와 대조했다. 이 진단 절차는 본시험 데이터 보충에 사용하지 않았다. [GCS 복원 API](https://docs.cloud.google.com/storage/docs/json_api/v1/objects/restore)

## Barman·모델·접속 준비

- **백업 함수 권한:** 함수 권한은 DB별이므로 관리자 DB에 준 권한만으로 새 논리 DB의 `pg_backup_start/stop`가 허용되지 않았다. 시험 DB에만 필요한 권한을 부여한 뒤 다시 점검했다. 공개 역할 권한을 넓히지 않았다.
- **Barman 잠금:** macOS Docker 바인드 디렉터리에서 경쟁 잠금이 감지되지 않았지만 컨테이너 네이티브 잠금 디렉터리에서는 감지됐다. 잠금 위치를 분리했다. 디렉터리 fsync는 두 위치 모두 통과했으므로 fsync 결함으로 설명하지 않는다.
- **도구 누락:** 최소 클라이언트 이미지에 `file` 실행 파일이 없어 WAL 처리 단계가 막혔다. 필요한 `file`·`rsync`를 추가한 전용 이미지에서 `DONE`과 `pg_verifybackup=0`을 확인했다. PostgreSQL 서버를 다른 제품으로 바꾸지 않았다.
- **제어 프록시:** 첫 모델 프록시는 chunked HTTP body를 전달하지 못했다. 전송 처리를 수정하고 실제 BGE로 인덱싱을 재확인했다. 당시 HTTPError만 수집한 회차의 구체적 상태 코드를 사후 추정하지 않는다.
- **초기 JVM 기동:** 첫 120초 대기 한도 안에서는 readiness가 오지 않았지만 같은 프로세스가 나중에 준비됐다. 본시험 초기 준비는 600초 한도에서 검사했고, 실제 277.613초 후 readiness와 리스너 소유권을 확인했다.
- **SQL 연결:** 도구 컨테이너의 새 PostgreSQL 연결에서 SQLSTATE 없는 OperationalError가 간헐적으로 관측됐다. IPv6 가설은 확인되지 않았다(AAAA 0개, 연결 비교 4/4 통과). 원인 미확정으로 남기고 원본 제어 SQL에는 이미 검증한 SSH·Unix 소켓 경로를 사용했다.

본시험의 receiver 준비 결과는 Barman 24/24, streaming receiver 1개다. 이 결과는 장애 뒤 재연결·장기 운영 경보·영구 WAL 보관 정책까지 검증한 것이 아니다. 각 준비·재시도의 개별 JSON과 이벤트는 해당 실행 디렉터리에 분리한다.

## 증거의 공개 범위

원시 앱·SSH 출력은 허용한 이벤트·상태·건수로 사전 축약하거나 사전 마스킹한 뒤 기록했다. 공개 사본은 자격 증명·내부 주소·프로젝트·버킷·호스트 식별자·개인 경로를 다시 검사한 파생 자료다. 인증정보·라이선스·백업 바이트·비공개 접속 연산자는 업로드하지 않는다. 따라서 수집하지 않은 원시 출력으로 세부 원인을 소급 재구성할 수 없다.
