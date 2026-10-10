# Vercel 업로드 범위 축소 검증

- 실행 ID: `vercel-source-scope-20261010-01`
- 시각: 2026-10-10 KST (정확한 시작·종료 시각 미수집)
- 위치: 로컬 격리 작업 트리 `frontend/`, Vercel 배포 소스 UI
- 목적·기준: Vercel 빌드에 불필요한 로컬·Cloudflare 파일과 환경 파일이 배포 소스에 포함되지 않는지 확인
- 명령·절차: `npx --yes vercel@63.1.0 deploy --dry --json --no-color`로 업로드 목록 점검; 최종 배포의 Vercel Source 목록도 확인
- 관측: 이전 배포 Source에는 `.wrangler`, `build`, `dist`, `.openai`의 파일이 보였음. `.vercelignore`를 추가한 뒤 dry-run은 대상 67개, 무시 항목 11개로 집계했고 `.env.example`, `.openai/hosting.json`, `.wrangler/deploy`, `build/sites-vite-plugin.ts`, `dist` 내용을 무시 대상으로 표시함. 최종 Source UI에서도 해당 디렉터리의 파일 내용은 보이지 않음.
- 해석: 로컬 설정·생성 산출물의 신규 업로드 범위를 줄였음. 이미 생성됐던 이전 배포의 소스 보존 상태는 이 변경으로 지워지지 않음.
- 증거 한계: dry-run의 전체 JSON을 별도 파일로 보존하지 않음. 계정에 연결돼 생성된 로컬 `.env.local`은 최초 원격 배포 뒤 삭제했고, 원격 Source 목록에도 보이지 않았음.
