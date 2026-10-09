# Vercel 빌드 재시도: Tailwind import 실패

- 실행 ID: `vercel-build-20261010-02`
- 시각: 2026-10-10 KST (정확한 시작·종료 시각 미수집)
- 위치: 로컬 격리 작업 트리의 `frontend/`
- 목적·기준: Nitro Vercel 산출물 생성, 종료 코드 0
- 명령: `NITRO_PRESET=vercel npm run build:vercel`
- 관측: 종료 코드 1. RSC 빌드에서 `@import "tailwindcss"`를 파일 경로로 해석해 `ENOENT` 발생.
- 해석: Vercel 전용 Vite 구성에 Tailwind Vite 플러그인이 필요했음. 호환되는 공식 플러그인 추가 후 재실행함.
- 증거 한계: 원문 전체를 별도 파일로 기록하지 않았다.
