# Vercel 원격 프로덕션 빌드·배포

- 실행 ID: `vercel-production-20261010-01`
- 시각: 2026-10-10 KST (정확한 시작·종료 시각 미수집)
- 위치: 로컬 CLI가 소스를 전송, Vercel 원격 빌드 지역 `iad1`
- 목적·기준: 연결된 기존 프로젝트에서 Linux 원격 빌드를 완료하고 `READY` 및 프로덕션 별칭을 받기
- 명령: `npx --yes vercel@63.1.0 link --yes --team gimini-3 --project zippy-lute-8okpbzh`; `npx --yes vercel@63.1.0 deploy --prod --yes --no-color`
- 관측: 기존 프로젝트 연결 성공. 배포 파일 131개 다운로드 후 원격 빌드 23초, Nitro `nodejs24.x` 함수 생성, 배포 종료 코드 0과 `readyState: READY`. `https://zippy-lute-8okpbzh.vercel.app/` 별칭 지정.
- 경고: 설치 스크립트 허용 대상과 선택적 `traceInclude` 의존성 해석에 관한 경고가 있었으나 빌드는 성공함. 프로젝트는 Hobby였고 Pro 전환은 하지 않음.
- 정리: 연결 과정에서 생성된 로컬 `.env.local`은 배포 후 삭제·부재 확인. 내용은 출력하거나 Git에 추가하지 않음.
- 해석: 이전 로컬 빌드와 달리 Vercel Linux 환경에서의 원격 빌드·배포가 성공함. 사용자 기능은 별도 확인이 필요함.
- 증거 한계: CLI 원문 전체를 보존하지 않았고, 배포 요금 청구액은 확인하지 않음.
