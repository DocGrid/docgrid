# 다중 문서 Worker 장애 시험 준비 로그

- 실행 시각: 2026-10-04 08:05~08:27 KST
- 위치: GCP 내부 부하 VM의 시험 전용 BGE-M3 서비스, 앱 A의 시험 전용 Worker, OpenSQL primary
- 목적: 실제 PDF·DOCX 인덱싱 기준선을 확보한 다음에만 DB 장애를 주입
- 비밀정보: 계정·토큰·내부 주소·프로젝트 식별자는 기록하지 않음

| 시각(KST) | 점검 또는 조치 | 관측 결과 | 판정 |
| --- | --- | --- | --- |
| 08:05~08:13 | 시험 전용 BGE-M3 서버 기동 | `/tmp` 실행은 SELinux가 거부했고, 시스템 Python 3.9는 서버 코드 문법을 해석하지 못함. 시스템 Python은 변경하지 않고 `/opt`의 Python 3.11 가상환경으로 재기동 | 준비 실패 2건을 수정 후 재실행 |
| 08:13 | BGE-M3 `/health/ready`, 단건 `/embed`, 앱 VM에서 준비 확인 | HTTP 200, 실제 `BAAI/bge-m3`·1024차원 모델 | 통과 |
| 08:16~08:17 | 기존 배포 JAR로 PDF 8·DOCX 4 업로드 후 Worker 실행 | 업로드 12/12 HTTP 201, 인덱싱 12/12 `FAILED`, Batch 요청 HTTP 422 | 장애 시험 중단 |
| 08:21 | 별도 PDF·DOCX 각 1건으로 요청 형식 진단 | 업로드 2/2 HTTP 201, FastAPI는 본문을 JSON 객체로 보지 못해 `model_attributes_type` 2건 | 배포 버전 불일치 진단 |
| 08:23 | 현재 `develop` 소스의 `bootJar` 빌드 | `BUILD SUCCESSFUL`, SHA-256 `24ecf7767c80c99eedf455fb4f1aac3f5a6ec726f4e0a89c0cdb10f5937f0cd9` | 시험 전용 Worker만 교체 |
| 08:26 | 새 JAR로 PDF·DOCX 각 1건 기준선 | 2/2 `INDEXED`, 청크 3·임베딩 3, 중복 0 | 장애 시험 진행 허용 |

기존 앱 A/B 서비스는 교체하지 않았다. 현재 소스에는 JSON `Content-Type` 고정 수정(커밋 `32ccc58`)이 이미 포함되어 있어 새 제품 코드 수정은 이 준비 단계에서 하지 않았다. 기존 배포 JAR가 현재 소스와 달랐다는 결론은 구·신 JAR의 실행 결과와 해시가 다른 점에 근거하며, 구 JAR의 빌드 커밋 자체는 확인하지 못했다.
