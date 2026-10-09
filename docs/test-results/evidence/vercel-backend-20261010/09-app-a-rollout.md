# 백엔드 A 순차 배포

- 실행 ID: `vercel-backend-app-a-20261010-01`
- 실행 시각: 2026-10-10 KST. 초 단위 실행 시각은 별도로 보존하지 않았다.
- 위치·대상: GCP 앱 VM A, 기존 내부 LB의 첫 대상.
- 목적·통과 기준: 기존 JAR SHA를 검증하고 A만 교체, 관리 health 200과 WebSocket Upgrade 101 확인 후 B로 진행.
- 명령·절차: 임시 OS Login 키로 IAP SSH 연결, 후보 JAR SHA 검증, `deploy_public_frontend_origin_vm.sh` 실행, 로컬 health·Upgrade 및 LB 상태 조회.
- 관측: `DEPLOY_APPLIED health=200 backup=present restore_timer=armed`. 새 JAR SHA 검증 성공, 관리 health 200, loopback WebSocket 101. 배포 후 LB 대상 2대 정상. 최종 점검에서 서비스 active·롤백 타이머 inactive.
- 해석: A가 새 Origin을 받아들이는 JAR로 교체됐다. VM 전체 기능·장애 복구 보장은 아니다.
- 정리: 전송용 임시 JAR·스크립트 삭제. 기존 JAR의 정확한 복사본은 VM 내부 백업으로 보존했다.
