# 업로드 범위 축소 후 프로덕션 재배포

- 실행 ID: `vercel-production-20261010-02`
- 시각: 2026-10-10 KST (정확한 시작·종료 시각 미수집)
- 위치: 로컬 CLI → Vercel 원격 빌드 지역 `iad1`
- 목적·기준: `.vercelignore`가 적용된 소스로 같은 프로덕션 프로젝트를 재배포하고 `READY`·도메인 별칭을 확인
- 명령: `npx --yes vercel@63.1.0 deploy --prod --yes --no-color`
- 관측: Vercel이 배포 파일 67개를 받아 빌드했으며 빌드 단계는 30초. CLI 종료 코드 0, `readyState: READY`, 기존 공개 URL `https://zippy-lute-8okpbzh.vercel.app/`에 다시 별칭 지정.
- 경고: 선택적 의존성의 `traceInclude` 해석과 설치 스크립트 관련 경고는 남았으나 빌드 실패는 없었음.
- 해석: 산출물 제외 후에도 Vercel Linux 원격 빌드와 배포가 동작함.
- 증거 한계: 이 실행의 전체 원문 로그를 보존하지 않았고, 프런트와 GCP 백엔드의 E2E 기능은 이 단계에서 검증하지 않음.
