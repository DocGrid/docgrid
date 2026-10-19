# 회수 전 접수한 작업의 회수 후 첫 모델 전달

## 목적

권한 회수 후 서버의 새 응답 또는 모델 입력에 해당 문서의 합성 표식이 없어야 한다. 계속 허용한 문서는 정상이어야 한다.

## 테스트 환경

- 실행 ID: `AUTH-QUEUED-20261019-R2`
- 리비전: `1272a016e3b83e3f028d45a3bf567dab6bee743f`
- 시작: 2026-10-19T22:11:21.892865+09:00
- 최종 판정: 2026-10-19T22:11:22.294195+09:00
- 로컬 새 PostgreSQL·Valkey, 실제 로그인·JWT·루프백 HTTP·문서 권한·검색 저장 조회·RAG 처리.
- 질의 임베딩·검색 후보·파일 바이트·모델 응답은 합성 대역. 실제 모델 HTTP client와 수신 서버는 실행했다.

## 테스트 방법

```bash
./backend/gradlew -p backend test --tests com.opensource.docgrid.domain.permission.integration.PermissionSavedResponseVerificationTest --no-daemon --offline --console=plain
```

비공개 문서 직접 권한 부여 → 실제 검색·답변 준비 → 소유자 회수 API 204 → 권한 행 0개·직접 접근 403 확인 → 대상 경로 확인.

## 관측 결과

**보안 합격 기준: 불합격. 문제 재현: 확인.**

권한 회수 204와 직접 접근 403을 확인한 뒤 실제 OllamaClient가 보낸 모델 요청 1/1건에 회수 표식이 남았다.

[실행 중 기록한 이벤트](events.jsonl)

- [모델 입력 1](model-input-1.json)

## 검증 과정에서 확인된 사항

모델 호출 전에는 요청 수가 0건인 것을 단언했다. 작업 처리는 실제 claimNext/processJob을 시험이 직접 호출했으며 스케줄러는 기동하지 않았다.

제품 코드를 수정하지 않았다. 임시 컨테이너 정리와 기존 환경 보존은 [전체 보고서](../README.md)에 연결했다. 실제 OpenSQL·OpenProxy·공개 서비스·GCS·실제 Ollama 추론·브라우저·스케줄러·알림은 시험하지 않았다.
