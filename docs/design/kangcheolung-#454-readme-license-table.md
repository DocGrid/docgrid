# #454 README 외부 구성요소 라이선스 표 보강

closes #454

#450, #452(임베딩 서버 torch)와 이어지는 2차 평가 오픈소스 라이선스 대응이다.

---

## 배경

- 평가 기준은 OSI 승인 라이선스이고(#399), 팀 점검에서는 대회 규정이 사용한 라이브러리·모델의 출처와 라이선스 명시도 요구한다고 정리했다. 규정 원문은 레포에서 확인하지 못했으므로 원문 대조가 필요하다.
- README의 라이선스 표에는 모델 2개와 OpenSQL, "Java·npm 의존성과 Container Image는 별도 라이선스 적용"이라는 포괄 항목만 있었다. 실제 사용하는 Python 패키지와 컨테이너 이미지의 라이선스는 없었다.

---

## 결정

**코드와 설정은 바꾸지 않고, README의 라이선스 표만 보강한다.**

MinIO는 건드리지 않는다. 팀이 사용하지 않고 배포 컨테이너에도 없으며(파일 저장소는 GCS), `docker-compose.yml`·`.env.example`·README 빠른 실행을 바꾸면 팀원의 로컬 개발 흐름에 영향이 있어 팀과 먼저 상의가 필요하다. 대신 확인한 사실을 라이선스 표에 정직하게 기록한다.

### MinIO 관련 확인 사실 (2026-10-09)

공식 `minio/minio` 이미지를 새로 받을 수 없다.

| 시도 | 결과 |
|---|---|
| `docker pull minio/minio:latest` | `pull access denied for minio/minio, repository does not exist or may require 'docker login'` |
| `docker pull --platform linux/amd64 minio/minio:RELEASE.2025-09-07T16-13-09Z` | 같은 오류 |
| Docker Hub 레지스트리 API(익명 토큰) 태그·매니페스트 조회 | 401 `UNAUTHORIZED` |
| `quay.io/minio/minio` 태그 조회 | 401 `Requires authentication` |

- 이미지를 미리 받아 둔 환경(개발 PC)에서만 동작해 드러나지 않았다. 이미지를 갖고 있지 않은 환경에서 `docker compose up`이 MinIO를 받지 못할 가능성이 크다.
- 저장소가 없어졌는지 비공개로 바뀌었는지는 구분하지 못했고, Docker 로그인 상태에서의 결과는 확인하지 못했다.
- 따라서 `latest` 태그를 릴리스 태그로 고정하는 방법은 쓸 수 없다.
- MinIO 서버 이미지는 AGPL-3.0이다. (로컬 컨테이너의 `minio --version` 출력과 저장소 `LICENSE`로 확인)
- MinIO 어댑터는 `STORAGE_TYPE=minio`일 때만 활성화되고(`@ConditionalOnProperty`), 앱 기본값이 이미 `local`이므로 MinIO 없이 실행하는 경로가 코드에 있다. 이 사실을 바탕으로 한 기본 경로 분리(compose profile, `.env.example`, README 빠른 실행 변경)는 팀 협의 후 별도 이슈로 진행한다.

---

## 변경 범위

| 파일 | 변경 |
|---|---|
| `README.md` | `## 라이선스 및 외부 구성요소` 표에 Ollama, Python·Java·npm 의존성, 컨테이너 이미지 항목 추가, 기준일 명시 |

### 추가한 항목과 확인 근거

| 항목 | 라이선스 | 확인 근거 |
|---|---|---|
| Python 의존성 (fastapi, uvicorn, FlagEmbedding, torch, transformers, peft, numpy, prometheus-client) | MIT, BSD, MIT, BSD, Apache-2.0, Apache-2.0, BSD, Apache-2.0 AND BSD-2-Clause | 핀 버전의 PyPI 메타데이터. FlagEmbedding 1.2.11은 PyPI 라이선스 칸이 비어 있어 소스 배포 파일(SHA-256 대조)의 `LICENSE`로 MIT 확인 |
| Java 의존성 | Apache-2.0·MIT·BSD 위주, Hibernate ORM 6.6 LGPL-2.1+, Logback EPL-2.0·LGPL-2.1 듀얼, Jakarta·AspectJ EPL-2.0, H2 MPL-2.0·EPL-1.0(테스트), JUnit EPL-2.0(테스트) | Gradle `runtimeClasspath`·`testRuntimeClasspath`의 POM·상위 POM |
| npm 의존성 | 실행 의존성 4개 MIT·Apache-2.0, 나머지 빌드·개발 도구 | `frontend/package-lock.json` 650개 |
| Valkey | BSD-3-Clause | `valkey-io/valkey` `COPYING` |
| pgvector | PostgreSQL License | `pgvector/pgvector` v0.8.1 `LICENSE` |
| Prometheus v3.5.5, Alertmanager v0.33.1 | Apache-2.0 | 각 태그의 `LICENSE` |
| Grafana 13.2.3 | AGPL-3.0 (기본 라이선스) | v13.2.3 `LICENSING.md` |
| Ollama | MIT | `ollama/ollama` `LICENSE` |
| MinIO | AGPL-3.0 | 위 확인 사실 |

---

## 검증

| 항목 | 결과 |
|---|---|
| 표의 각 라이선스 | 위 "확인 근거"의 업스트림 원문 또는 패키지 메타데이터와 일치 |
| 변경 파일 | `README.md` 하나. 코드·설정 변경 없음 |

### 검증하지 못한 범위

- 라이선스는 메타데이터와 업스트림 원문 기준이며 법률 자문이 아니다. 제출 시점에 다시 확인한다.
- Java 의존성 라이선스는 POM 표기 기준이다. JAR 내부의 고지 파일과 개별 배포 조건은 전수 확인하지 않았다.
- npm 개발 도구 중 CC0·CC-BY 패키지가 최종 배포 번들에 들어가는지는 로컬 빌드 산출물(`dist`, `.next`) 검색으로만 확인했고, 검색에서는 발견되지 않았다.

---

## 이 이슈의 범위 밖

- 전이 의존성을 포함한 라이브러리별 **전체 구성요소 목록**: 규정 제8조의 요구 범위를 원문으로 확인한 뒤 필요하면 진행
- MinIO 기본 경로 분리(compose profile, `.env.example`, README 빠른 실행)와 MinIO 기반 E2E(`storageWorkerE2eTest`, `local-e2e`)의 대체 방안: 팀 협의 후 별도 이슈
- Grafana(AGPL-3.0)의 의무 범위는 OpenUP 멘토링(~10/26)에서 확인
- `docs/images/architecture.png`의 Redis 로고, GCP 운영 Redis 현재 버전 확인
