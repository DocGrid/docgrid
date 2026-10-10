# 백엔드 B 순차 배포

- 실행 ID: `vercel-backend-app-b-20261010-01`
- 실행 시각: 2026-10-10 KST. 초 단위 실행 시각은 별도로 보존하지 않았다.
- 위치·대상: GCP 앱 VM B, 기존 내부 LB의 두 번째 대상.
- 목적·통과 기준: A 정상 확인 후 B만 교체, 관리 health 200과 WebSocket Upgrade 101, 최종 LB 2/2 확인.
- 명령·절차: 임시 OS Login 키로 IAP SSH 연결, 후보 JAR SHA 검증, 동일 배포 스크립트 실행, 로컬 health·Upgrade 및 LB 상태 조회.
- 관측: `DEPLOY_APPLIED health=200 backup=present restore_timer=armed`. 새 JAR SHA 검증 성공, 관리 health 200, loopback WebSocket 101. 최종 `get-health`에서 `HEALTHY` 2/2. 최종 점검에서 서비스 active·롤백 타이머 inactive.
- 해석: B도 동일 JAR로 교체됐으며 내부 LB가 두 대상 모두를 정상으로 본다.
- 정리: 전송용 임시 JAR·스크립트 삭제. 기존 JAR 백업은 VM 내부에 보존했다.
