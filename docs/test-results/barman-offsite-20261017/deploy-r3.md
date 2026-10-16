# Barman 외부 보존 배포 상태 확인 · DEPLOY-R3

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | R2의 마지막 확인 실패 뒤 실제 설치·권한·unit 상태를 읽기 전용 검증 |
| 실행 위치 | GCP 시험용 Barman 백업 VM |
| 절차 | `sudo test`로 4개 설치 파일 확인, `sudo stat`으로 설정 모드 확인, `systemd-analyze verify`, timer 활성 여부 확인 |
| 실행 ID | `BARMAN-OFFSITE-DEPLOY-20261017-R3` |
| 성공 기준 | 도구·service·timer·설정 4/4 존재, 설정 0640, unit 검증 성공, timer 아직 비활성 |

## 관측 결과

2026-10-17 00:48 KST 실행에서 도구·service·timer·설정 4/4 존재, 설정 모드 0640, systemd 검증 성공, timer 비활성을 확인했다. 배포 파일은 설치됐지만 실제 동기화는 이 실행에서 하지 않았다.
