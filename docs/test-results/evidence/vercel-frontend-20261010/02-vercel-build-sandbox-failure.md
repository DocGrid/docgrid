# Vercel 빌드 첫 시도: 샌드박스 쓰기 제한

- 실행 ID: `vercel-build-20261010-01`
- 시각: 2026-10-10 KST (정확한 시작·종료 시각 미수집)
- 위치: 로컬 격리 작업 트리의 `frontend/`
- 목적·기준: Nitro Vercel 산출물 생성, 종료 코드 0
- 명령: `NITRO_PRESET=vercel npm run build:vercel`
- 관측: 종료 코드 1. Vite 설정 로드 전에 `node_modules/.vite-temp` 생성이 `EPERM`으로 거부됨.
- 해석: 코드 검증 이전의 로컬 샌드박스 쓰기 제한. 권한 확장으로 동일 명령을 재실행함.
- 증거 한계: 원문 전체를 별도 파일로 기록하지 않았다.
