# 외부 보존 복원 stage의 시험 Standby 전송 · TRANSFER-R1

## 실행 전 계획 (2026-10-17 02:48 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-TRANSFER-R1` |
| 목적 | 외부 백업과 WAL만으로 구성한 복원 PGDATA를 라이선스가 허가된 시험 Standby의 별도 경로로 전달 |
| 위치·방법 | 백업 VM 격리 stage → 로컬 메모리 스트림 → Standby 별도 stage. 압축 스트림 종료 코드·파일 수·해시 대조. 원본 DB 데이터 경로 사용 금지 |
| 합격 기준 | 송·수신 종료 코드 0, 대상 별도 stage의 `recovery.signal`과 WAL/history 9개, 파일 수·용량 일치 |
| 보호 | SSH 임시 키는 종료 시 제거. 출력에 VM 이름·주소·경로·객체명·내용을 기록하지 않음. Standby의 기존 Patroni·PostgreSQL은 이 단계에서 그대로 실행 |

## 관측

2026-10-17 **02:48:11~02:48:58 KST**에 압축 스트림 **29,098,365바이트**를 전송했다. 송신·수신 모두 파일 **1,904개**, 전체 **217,841,016바이트**이고 전체 트리 SHA-256이 **일치**했다. 대상 WAL/history **9개**, `recovery.signal` 존재, 양쪽 명령 종료 코드 0. 임시 OS Login 키 **2/2개 제거**. **통과**. 기존 Standby의 Patroni·PostgreSQL은 이 단계에서 멈추지 않았다.
