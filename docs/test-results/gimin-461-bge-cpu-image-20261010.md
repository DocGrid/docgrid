# BGE-M3 CPU 이미지 의존성 호환성 검증

- 관련 이슈: [#461](https://github.com/DocGrid/docgrid/issues/461)
- 기준 코드 커밋: `6fd586da24dccb4632c3c548a6cd2d83a96766a3`
- 환경: GCP 시험 전용 CPU VM 1 vCPU·6 GiB RAM, Docker 컨테이너 제한 1 CPU·4 GiB RAM
- 시간대: 2026-10-10 KST
- 목적: 저장소 기본 Dockerfile만으로 이미지 빌드와 실제 BGE-M3 단건·배치 계약을 재현한다.
- 공개 범위: 내부 주소·호스트명·프로젝트 ID·자격증명·벡터 원문은 기록하지 않는다.

## 결론

저장소 `requirements.txt`에 `pyarrow==21.0.0`을 고정하고 Docker 빌드 중
`FlagEmbedding` import를 확인한 이미지가 별도 임시 덮어쓰기 없이 빌드·기동했다.
`/health/ready`, `/embed`, `/embed/batch`가 200을 반환하고 1024차원 유한
벡터와 4건 배치 순서를 충족했다.

단, 새 컨테이너의 첫 단건 요청은 16,461.51 ms로 앱의 조회 제한시간 5 s를
초과했다. 기능 계약 통과와 조회 지연은 별개의 판정이다. 이 단일 표본으로
p95·동시 처리량·공개 화면의 검색 성공을 주장하지 않는다.

| 실행 ID | 목적·위치 | 방법 | 관측 | 판정 |
| --- | --- | --- | --- | --- |
| `bge461-build-r1` | 의존성 빌드, CPU VM | 저장소 소스만 전송·SHA-256 일치 확인 후 `docker build --quiet` | 이미지 생성 2026-10-10 04:17:38 KST, 빌드 단계 import 성공 | PASS |
| `bge461-api-r1` | 실제 모델·HTTP 계약, CPU VM | 기존 컨테이너 중지 → 새 이미지를 별도 loopback 포트에서 실행 → 단건·4건 배치 → 자동 원복 | readiness 200, 단건 200·1024차원·16,461.51 ms, 배치 200·4건·7,007.96 ms | 기능 PASS / 첫 조회 5 s 예산 WARN |
| `bge461-unit-r1` | Python API 회귀, CPU VM 격리 컨테이너 | 새 이미지에 시험 의존성만 임시 설치 후 `pytest -q test_main.py` | 22 passed, 2 deprecation warnings, 5.41 s | PASS |

## 재현 방법과 원복

1. `backend/embedding-server`를 이미지 빌드 문맥으로 사용한다. 수정된
   `requirements.txt`와 `Dockerfile`의 전송 전후 SHA-256을 비교한다.
2. `docker build --quiet --tag docgrid-embedding:cpu-fix461 backend/embedding-server`
   를 실행한다. 빌드의 `FlagEmbedding` import가 실패하면 이미지가 완성되지 않는다.
3. 기존 모델 캐시를 재사용하되 기존 서비스와 포트를 분리해 새 컨테이너를 실행한다.
   `/health/ready`, `/embed`, `/embed/batch`를 고정 합성 입력으로 호출한다.
4. 시험 컨테이너를 제거하고 기존 컨테이너를 재시작한다. 종료 시 기존 컨테이너
   `running`·재시작 0회, 시험 컨테이너 없음이 확인됐다.

세부 실행·결과는 [빌드 로그](evidence/issue-461/bge461-build-r1.md)와
[API 로그](evidence/issue-461/bge461-api-r1.md),
[회귀 테스트 로그](evidence/issue-461/bge461-unit-r1.md)에 목적별로 분리했다.

## 기존 실패와 한계

- 기존 이미지에서는 `numpy==1.26.4`와 설치된 `pyarrow==26.0.0`의
  import 충돌로 서버가 시작되지 않았다. VM 전용 `pyarrow==21.0.0` 덮어쓰기로
  확인했던 성공은 저장소 기본 이미지의 성공으로 계산하지 않는다.
- 로컬 Docker 엔진이 실행 중이지 않아 이 실행은 GCP 시험 VM에서 수행했다.
- 앱 A/B 연결, Worker 인덱싱, 실제 PDF·검색·다운로드, 인증 WebSocket은
  이 이슈 범위에서 아직 시험하지 않았다.
- 일시적 SSH 공개키는 새 시험 VM 한 대에만 2시간 만료 조건으로 등록했다.
  작업 종료 전에 VM 메타데이터와 로컬 개인키를 모두 제거한다.
