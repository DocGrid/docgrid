# RAG 클라이언트 heartbeat 회귀 — `rag473-frontend-r1`

- 목적: STOMP CONNECT에 선언한 10초 heartbeat를 실제로 전송하도록 바꾼 프런트가 빌드·회귀하는지 확인한다.
- 위치·시각: 로컬 Node 26, 2026-10-10 약 05:39~05:40 KST.
- 완료 기준: 변경 파일 lint 0, 실제 프런트 build 성공, 저장소 테스트 실패 0.

| 실행 명령 | 결과 | 해석 |
| --- | --- | --- |
| `npm ci --ignore-scripts` | lockfile 기준 471개 패키지 설치 성공. 감사 경고 30건(낮음 1·보통 6·높음 23) | 설치 성공; 기존 의존성 보안 감사 경고는 이번 heartbeat Fix로 해결하지 않음 |
| `npx eslint app/lib/useRagAnswerSocket.ts` | 종료 0 | 변경 파일 lint 통과 |
| `npx tsc --noEmit` | 실패: TypeScript incremental 파일 쓰기 권한, Cloudflare 전용 모듈 및 기존 테스트의 `.ts` import 설정 오류 | 전역 typecheck 통과를 주장하지 않음 |
| `npm run build` | 5단계 Vinext 빌드 성공 | 실제 번들 생성 통과 |
| `npm test` | Vinext build 성공 뒤 **49/49 통과**, 실패 0 | 저장소 프런트 회귀 통과. 브라우저의 새 번들 heartbeat 동작은 별도 배포 확인 필요 |

빌드에서 Node의 `module.register()` 사용 중단 경고와 일부 동적 API 경로 분류 경고가 있었지만 빌드·테스트는 성공했다.
