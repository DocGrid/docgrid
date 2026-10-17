# 격리 PGDATA 소유권·설정 점검 · STAGE-OWNER-R1

## 실행 전 계획 (2026-10-17 04:12 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-STAGE-OWNER-R1` |
| 목적 | 첫 격리 DB 기동의 권한 거부를 별도 PGDATA 소유권 문제로 확인·수정 |
| 위치·방법 | 시험 Standby의 원래 data 소유권을 읽고 새 stage에만 재귀 적용. 같은 허가된 PostgreSQL 바이너리로 data_directory·port·listen_addresses·archive_mode 설정을 실제 해석 |
| 합격 기준 | stage 소유권 일치, 네 설정 모두 기대값, 원래 data 소유권 불변, Patroni 정상 실행 |
| 보호 | 원래 PGDATA 내용과 사용자 DB 상태는 변경하지 않음. 검증 실패 시 재기동 시험 중단 |

## 관측

2026-10-17 04:12:05~04:12:14 KST에 별도 stage 소유권을 원래 data와 일치시켰다. `data_directory`·`port`·`listen_addresses`·`archive_mode` **4/4 설정 해석 통과**, 원래 data 존재·소유권 불변, 임시 키 제거 성공. **통과**.
