# #450 임베딩 서버 torch를 CPU 전용으로 교체해 NVIDIA 독점 패키지 제거

closes #450

---

## 배경

대회의 오픈소스 라이선스 평가 기준은 OSI 승인 라이선스이고, 평가는 각 평가 시점의 저장소 상태로 이루어진다. (#399 참고)

`backend/embedding-server/requirements.txt`의 `torch==2.4.1`을 linux/amd64에서 설치하면 NVIDIA CUDA 런타임 패키지 12개가 의존성으로 함께 설치된다.

```text
nvidia-cublas-cu12 12.1.3.1      nvidia-cufft-cu12     11.0.2.54
nvidia-cuda-cupti-cu12 12.1.105  nvidia-curand-cu12    10.3.2.106
nvidia-cuda-nvrtc-cu12 12.1.105  nvidia-cusolver-cu12  11.4.5.107
nvidia-cuda-runtime-cu12 12.1.105 nvidia-cusparse-cu12 12.1.0.106
nvidia-cudnn-cu12 9.1.0.70       nvidia-nccl-cu12      2.20.5
nvidia-nvjitlink-cu12 12.9.86    nvidia-nvtx-cu12      12.1.105
```

- 이 패키지들의 라이선스는 NVIDIA 독점(NVIDIA Proprietary Software)이며 OSI 승인이 아니다. (PyPI 메타데이터 기준)
- 개발 PC(Apple Silicon, linux/arm64) 이미지에는 설치되지 않아 드러나지 않았다. 변경 전 이미지에서 `pip list | grep -ci nvidia`는 arm64 `0`, amd64 `12`였다.
- 임베딩 서버는 `docker-compose.yml`에서 GPU 없이 CPU만 사용하므로(`cpus`, `mem_limit`) 이 패키지는 실행에 쓰이지 않는다. (`torch.cuda.is_available()` → `False`)

---

## 결정

**torch 버전은 유지하고, PyTorch CPU 전용 저장소를 추가 인덱스로 지정한다.**

`requirements.txt` 맨 위에 한 줄을 추가한다.

```text
--extra-index-url https://download.pytorch.org/whl/cpu
```

- `torch==2.4.1`이 CPU 전용 빌드 `2.4.1+cpu`로 선택된다. 이 빌드는 `nvidia-*`와 `triton`을 요구하지 않는다.
- `Dockerfile`이 `pip install -r requirements.txt`를 쓰고, `requirements-test.txt`도 `-r requirements.txt`로 이 파일을 참조하므로 **이미지와 로컬 테스트가 같은 설정을 쓴다.**
- 서버 코드(`main.py`)와 API 계약은 변경하지 않는다.

### 선택하지 않은 방법

- **`Dockerfile`의 `pip install`에만 인덱스를 지정**: 로컬 테스트 환경(`requirements-test.txt`)이 GPU용 torch를 받아 이미지와 어긋난다.
- **`--index-url`로 기본 인덱스를 교체**: PyPI의 나머지 패키지를 받지 못한다. 추가 인덱스로 충분하다.
- **torch 버전 변경**: 라이선스 문제가 버전이 아니라 빌드 종류에 있어 불필요하다.

---

## API 명세

변경 없음. `/embed`, `/embed/batch`, `/health*`, `/metrics`의 요청·응답·에러 계약은 그대로다.

---

## 변경 범위

| 파일 | 변경 |
|---|---|
| `backend/embedding-server/requirements.txt` | CPU 전용 torch 저장소 `--extra-index-url` 추가 |

---

## 검증

| 항목 | 결과 |
|---|---|
| linux/amd64 `pip install --dry-run` | 76개 → 63개. `nvidia-*` 12개와 `triton` 제거, `torch 2.4.1+cpu` 선택 |
| linux/amd64 이미지 빌드 | `pip list`의 `nvidia-*` **0개**(변경 전 12개), `torch 2.4.1+cpu`, `import torch` 성공, `cuda_available False` |
| linux/amd64 이미지 크기 | 3.16GB → **2.29GB** (약 0.87GB 감소) |
| 임베딩 값 비교 (linux/arm64, 고정 문장 6개, `/embed/batch`, 1024차원) | 변경 전·후 **모든 값이 완전히 일치** (`cos=1.0`, 최대 절대 오차 `0`) |
| `pytest test_main.py` (변경 후 이미지) | 22 passed |
| 기준 이미지 | 변경 전 이미지는 `HEAD`의 `requirements.txt`로 같은 방식으로 빌드해 비교 |

### 검증하지 못한 범위

- 임베딩 값 비교는 **arm64**에서만 수행했다. arm64 torch는 원래 CPU 빌드라 변경 전후 패키지 구성이 같다. 즉 이 비교는 서버 코드와 모델 동작이 그대로임을 확인한 것이며, **x86 CPU 빌드의 수치 일치**는 확인하지 못했다. amd64는 설치 구성, `import torch`, 이미지 크기까지만 확인했다. (에뮬레이션에서 BGE-M3 추론은 실행하지 않음)
- 배포 환경(GCP VM 등)에서의 실제 기동은 이 이슈에서 확인하지 않았다. 배포 시 `/health/ready`로 확인이 필요하다.

---

## 이 이슈의 범위 밖

- `minio/minio`(태그 미고정, AGPL-3.0), `grafana/grafana:13.2.3`(AGPL-3.0): OSI 승인 라이선스이나 별도 컨테이너 실행의 충돌 여부는 OpenUP 멘토링(~10/26)에서 확인한다.
- `docs/images/architecture.png`의 Redis 로고, README 라이선스 표 보강
- `FlagEmbedding==1.2.11`은 PyPI 라이선스 메타데이터가 비어 있다.
- 라이선스는 POM·PyPI·npm 메타데이터 기준이며 소스 스캔 결과와 다를 수 있다.
