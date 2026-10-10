# `475-cleanup-01` — 배포 원복 수단과 임시 접근 정리

- 목적: 최종 JAR을 유지하되 시험용 자동 원복·비밀 백업·임시 접근은 제거
- 위치·시각: GCP 앱 A/B와 로컬, 2026-10-10 약 06:14 KST. 명령 시작 초 단위 시각은 별도 수집하지 못했다.
- 방법: 두 앱에서 새 JAR 해시·readiness 재검증 → 각 VM의 30분 원복 타이머 중지 → 임시 설정 백업·후보 JAR·배포 스크립트 제거. A/B 로컬 터널 종료, 등록한 OS Login 공개키 한 개와 로컬 키쌍 삭제, Vercel 임시 OIDC 파일 삭제.
- 성공 기준: A/B 같은 새 SHA, 서비스 active, 타이머 inactive, 임시 설정 백업 없음, 해당 OS Login 키 0개.
- 원본 요약: A/B 각각 `CONFIRMED readiness=200 rollback_timer=stopped backups=removed`; 재확인 A/B `active`·동일 SHA·`inactive`·`env_backup_removed`. OS Login 대상 지문은 제거 전 1개, 제거 후 0개였다.
- 해석: 새 앱 버전은 유지하며 시험 임시 자격·비밀 복사본은 제거했다. 이전 시험의 비밀 없는 JAR 백업은 이번 삭제 범위 밖이다.
