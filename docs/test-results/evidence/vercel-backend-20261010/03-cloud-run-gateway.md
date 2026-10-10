# Cloud Run 공개 게이트웨이 배포·경로 시험

- 실행 ID: `vercel-backend-cloud-run-20261010-02`
- 실행 시각: 2026-10-10 KST. 초 단위 최초 배포 시각은 별도로 보존하지 않았다.
- 위치·대상: GCP Cloud Run → Direct VPC egress → 기존 내부 HTTP LB → 백엔드 A/B.
- 목적·통과 기준: 공개 HTTPS 진입점에서 일반 API 접근, 인증 필요 경로의 거부, 시험·운영 진단 경로 차단. 최소 인스턴스 0·최대 인스턴스 2.
- 명령·절차: 전용 빌드 계정으로 `gcloud run deploy` 소스 빌드·배포 후 `curl`로 경로별 HTTP 상태 확인.
- 관측: 최종 `/__gateway_health` 204, `/departments` 200, `/auth/me` 401, `/actuator/health` 404, `/api/ha-probe` 404. 기존 내부 LB 대상 2대는 `HEALTHY` 2/2.
- 해석: 게이트웨이가 내부 백엔드에 도달한다. 401은 비인증 요청의 예상 응답이다. 공개 진입점이 모든 API를 안전하게 보호한다는 포괄적 보증은 아니다.
- 정리: 빌드 계정의 임시 `roles/run.builder` 바인딩을 철회해 잔여 0건 확인. 런타임 게이트웨이는 시연 접속을 위해 유지한다.
- 한계: 실제 계정 로그인, 큰 문서 업로드, Worker·GCS 경로는 이 실행에서 시험하지 않았다. 공개 URL·사설 인프라 식별자는 기록하지 않았다.
