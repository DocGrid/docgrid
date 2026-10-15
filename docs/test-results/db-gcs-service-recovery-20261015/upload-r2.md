# 새 업로드 R2

- **Run ID**: `DR-SERVICE-UPLOAD-20261015-R2`
- **시각**: 2026-10-15 17:28 KST 전후; GCS 객체 생성 시각 17:28:23 KST
- **실행 위치**: 로컬 격리 백엔드·복원 OpenSQL·독립 GCS
- **목적·방법**: 고유 합성 TXT 한 건을 `POST /api/documents`로 업로드하고 API 다운로드, DB FileObject, GCS 객체를 대조
- **성공 기준**: HTTP 201, 문서 버전·Job 각각 1건, 파일 크기·SHA-256 일치, GCS generation 존재

| 관측값 | 결과 |
| --- | --- |
| 업로드·다운로드 | HTTP 201·200 |
| 원본 크기 | 업로드·DB·GCS·다운로드 모두 129바이트 |
| 무결성 | 업로드↔다운로드 SHA-256 일치, DB 파일 해시↔GCS SHA-256 일치 |
| DB 원장 | 대상 버전 1건, 대상 Job 1건, FileObject 1건 |
| GCS 메타데이터 | 객체 존재, generation 존재 |

**판정**: 통과. 이 결과는 격리 복원본과 합성 입력의 결과이며 공개 서비스 배포의 검증은 아니다. 합성 객체는 마지막 정리에서 generation 조건으로 제거했다.
