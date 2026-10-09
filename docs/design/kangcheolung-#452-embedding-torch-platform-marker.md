# #452 임베딩 서버 torch를 플랫폼별 조건부 고정으로 바꿔 CPU 저장소 장애 시 NVIDIA 패키지 재유입 방지

closes #452

#450(PR #451)의 후속이다.

---

## 배경

#450에서 `requirements.txt`에 PyTorch CPU 저장소(`--extra-index-url https://download.pytorch.org/whl/cpu`)를 추가해, linux/amd64 이미지에서 `torch 2.4.1+cpu`를 받고 NVIDIA 독점 패키지(`nvidia-*`) 12개를 제거했다.

그러나 요구사항이 `torch==2.4.1`로 남아 있었다.

- `==2.4.1`은 CPU 저장소의 `2.4.1+cpu`뿐 아니라 PyPI의 일반 `2.4.1`도 만족한다. (PEP 440은 `==`에서 local version 표기를 무시한다.)
- pip는 기본 인덱스와 추가 인덱스에 우선순위를 두지 않는다. 사용할 수 있는 후보 중에서 고른다.
- 따라서 **CPU 저장소에 접속하지 못하면 pip는 경고 없이 PyPI의 일반 `2.4.1`을 선택하고**, 이 휠은 `nvidia-*` 12개를 의존성으로 끌어온다.

외부 접속이 제한된 서버나 CI에서 이미지를 빌드하면 라이선스 문제가 에러 없이 되살아난다.

### 재현 (병합된 `develop`의 `requirements.txt`, linux/amd64, 저장소 접속 불가)

접속 불가는 `download.pytorch.org`를 `127.0.0.1`로 돌려(`docker run --add-host`) 흉내 냈다.

| 결과 | 값 |
|---|---|
| `pip install --dry-run` 종료코드 | **0 (성공)** |
| 선택된 torch | 일반 `2.4.1` |
| `nvidia-*` | **12개** |
| 설치 패키지 수 | 76개 |

---

## 결정

**x86 Linux에서만 `+cpu`를 강제하고, 나머지 플랫폼은 기존 `torch==2.4.1`을 유지한다.**

```text
torch==2.4.1+cpu ; sys_platform == "linux" and platform_machine == "x86_64"
torch==2.4.1 ; sys_platform != "linux" or platform_machine != "x86_64"
```

- x86 Linux에서는 `+cpu` 라벨이 붙은 휠만 요구하므로, CPU 저장소에 접속하지 못하면 일치하는 휠이 없어 **설치가 실패**한다. 일반 휠로 넘어가지 않는다.
- 활성화되는 줄은 항상 정확히 1개다.
- `--extra-index-url` 줄은 유지한다.
- 환경 마커에는 `not`을 쓸 수 없다(pip가 `InvalidMarker`로 거부). 그래서 두 번째 줄은 첫 번째 조건의 반대를 `or`로 풀어 적는다.

### 선택하지 않은 방법

- **`torch==2.4.1+cpu`로 단독 고정**: x86에서는 문제를 막지만, CPU 저장소에는 linux/arm64와 macOS용 `+cpu` 휠이 없다(arm64용은 라벨 없는 `2.4.1`). 개발 PC(Apple Silicon)의 Docker 빌드가 `No matching distribution`으로 실패한다. 이 실패를 linux/arm64에서 재현했다.
- **Dockerfile에서 빌드 후 `nvidia-*`를 검사**: 마커 방식으로 핵심 위험이 막히므로 이번에는 추가하지 않는다. 이후 의존성 변화까지 잡고 싶으면 별도로 검토한다.
- **`--index-url`로 기본 인덱스를 교체**: PyPI의 나머지 패키지를 받지 못한다.

---

## API 명세

변경 없음. 서버 코드와 `/embed`, `/embed/batch`, `/health*`, `/metrics` 계약은 그대로다.

---

## 변경 범위

| 파일 | 변경 |
|---|---|
| `backend/embedding-server/requirements.txt` | `torch==2.4.1`을 플랫폼별 두 줄로 변경하고 주석 수정 |

`Dockerfile`과 `requirements-test.txt`(`-r requirements.txt`)는 변경하지 않는다. 두 곳 모두 같은 파일을 읽으므로 이미지와 로컬 테스트가 같은 조건을 쓴다.

---

## 검증

수정한 실제 `requirements.txt`와 `requirements-observability.txt`를 `python:3.11-slim`에서 `pip install --dry-run`으로 확인했다.

| 환경 | CPU 저장소 | 종료코드 | torch | `nvidia-*` | 판정 |
|---|---|---|---|---|---|
| linux/amd64 | 정상 | 0 | `2.4.1+cpu` | 0개 | 기대대로 |
| linux/amd64 | 접속 불가 | **1** | `No matching distribution found for torch==2.4.1+cpu` | - | **실패해야 하는 경로, 기대대로** |
| linux/arm64 | 정상 | 0 | `2.4.1` | 0개 | 기대대로 |
| linux/arm64 | 접속 불가 | 0 | `2.4.1` | 0개 | 기대대로 (arm64는 원래 GPU 패키지가 없음) |

실제 이미지 빌드와 테스트도 확인했다.

| 항목 | 결과 |
|---|---|
| linux/amd64 이미지 빌드 | 성공. `x86_64`, `torch 2.4.1+cpu`, `torch.version.cuda=None`, `nvidia-*` 0개 |
| linux/arm64 이미지 빌드 | 성공. `aarch64`, `torch 2.4.1`, `torch.version.cuda=None`, `nvidia-*` 0개 |
| **linux/amd64 + CPU 저장소 접속 불가 상태의 `docker build`** | **빌드 실패(종료코드 1), 이미지 생성 안 됨** |
| `pytest test_main.py` (`requirements-test.txt` 설치 포함) | amd64 22 passed, arm64 22 passed |

비교 기준으로 병합된 `develop`의 `requirements.txt`를 같은 조건(amd64, 저장소 접속 불가)에서 돌리면 위 "재현"의 결과(성공, `nvidia-*` 12개)가 나온다.

### 검증하지 못한 범위

- 임베딩 값 비교와 `/health/ready`는 이번에 다시 하지 않았다. x86에서 설치되는 torch는 #450에서 이미 검증한 `2.4.1+cpu`와 같은 휠이고, 이번 변경은 설치 조건만 바꾸므로 설치 결과가 달라지지 않는다는 판단이다. (#450 설계 문서의 amd64 에뮬레이션 검증 결과 참조)
- 접속 불가는 `--add-host`로 흉내 낸 것이며 실제 방화벽·프록시 환경에서 재현한 것은 아니다.
- 운영 x86 서버의 이미지는 이 변경만으로 바뀌지 않는다. 재빌드·재배포 후 `nvidia-*` 0개, `torch 2.4.1+cpu`, `/health/ready`, 임베딩 값 비교를 확인해야 한다.

---

## 이 이슈의 범위 밖

- README 라이선스 표 보강과 전이 의존성을 포함한 구성요소 목록, MinIO 이미지 태그 고정, 모델 버전 기록
- `docs/images/architecture.png`의 Redis 로고 교체
- MinIO·Grafana(AGPL-3.0)의 의무 범위는 OpenUP 멘토링에서 확인한다.
