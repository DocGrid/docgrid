# 임시 접근·배포 파일 정리

- 실행 ID: `vercel-backend-cleanup-20261010-01`
- 실행 시각: 2026-10-10 약 02시 KST.
- 위치·대상: GCP 앱 A/B, OS Login 프로필, 프로젝트 IAM, 개발 컴퓨터의 임시 키 파일.
- 목적·통과 기준: 임시 JAR·배포 스크립트와 접근 권한 제거, 실행 중인 A/B·Cloud Run 연결 유지.
- 명령·절차: A/B에서 정확한 임시 파일 두 개씩 삭제·부재 확인, OS Login `ssh-keys remove` 후 남은 키 수 비교, `projects remove-iam-policy-binding` 후 빌드 역할 바인딩 확인. 두 VM의 JAR SHA·서비스·타이머와 LB health를 재조회했다.
- 관측: A/B 임시 파일 각 2개 제거. 임시 OS Login 공개키 잔여 0건, 로컬 개인키 제거, 전용 빌드 계정의 임시 `roles/run.builder` 바인딩 잔여 0건. A/B 서비스 active, 새 JAR SHA 일치, 롤백 타이머 inactive, LB `HEALTHY` 2/2.
- 정리 범위: 복구 안전을 위해 VM 내부의 기존 JAR 백업은 유지했다. Cloud Run 런타임 계정과 공개 게이트웨이는 시연 접속을 위해 유지한다.
- 한계: 전용 빌드 서비스 계정 자체는 권한 없이 남아 있다. 이후 재배포에는 빌드 권한을 다시 검토해야 한다.
