# Standby 격리 stage 실행 준비 · STAGE-PREPARE-R1

## 실행 전 계획 (2026-10-17 02:50 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-STAGE-PREPARE-R1` |
| 목적 | 전달된 별도 PGDATA가 라이선스 허가된 Standby 런타임의 권한·localhost 설정으로 실행 가능한지 확인 |
| 위치·방법 | Standby 호스트에서 오직 복원 stage의 소유권을 기존 데이터 경로와 맞추고, OpenSQL `postgres -C`로 data directory·port·listen address·archive mode 해석 확인 |
| 합격 기준 | 별도 stage 소유권 일치, 기존 데이터 경로의 소유권 불변, localhost:15432·archive off 설정 4/4, 원래 Patroni·DB 실행 유지 |
| 보호 | 원본 PGDATA에는 쓰지 않음. 이 단계에서 Standby 프로세스를 중단하지 않음 |

## 관측

2026-10-17 **02:49:25~02:49:33 KST**에 별도 stage 소유권을 기존 데이터 경로 소유권과 맞췄다. OpenSQL 설정 해석은 data directory·port·listen address·archive mode **4/4 통과**했고, 기존 데이터 경로의 소유권은 바뀌지 않았다. 임시 OS Login 키 제거 성공. **통과**. 기존 Standby 프로세스는 실행 상태를 유지했다.
