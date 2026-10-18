# 독립 환경 로컬 시연 재현

## 목적

새 사용자가 통합 명령으로 시작·검증·종료를 재현할 수 있는지 실제 서비스에서 확인한다.
시연 성공 판정과 종료 소유권을 함께 확인하며, 로컬 PostgreSQL 결과를 OpenSQL HA 검증으로 확대하지 않는다.

## 테스트 환경

- GitHub hosted Ubuntu 24.04 임시 실행기, Java 17·Node 22·Python 3.
- PostgreSQL 17·pgvector 0.8.1, Valkey, 실제 BAAI/bge-m3 CPU 모델, Gradle 백엔드, 프런트엔드, Chromium.
- Embedding 컨테이너 CPU 상한 2.0, 기본 메모리 상한 4 GiB. 이는 호스트 전체 사양이나 처리량 벤치마크 수치가 아니다.
- 예제 15건: PDF 5·DOCX 5·HWPX 5. 기대 검색 질문 3개. 실제 Ollama는 기동하지 않는다.
- 개인 컴퓨터의 기존 Docker 자원이나 GCP 공개 서비스는 사용하지 않는다.
- 별도 macOS 점검은 실제 Gradle 도움말·제어 프로세스 계약 검사다. macOS 전체 서비스 E2E와 구분한다.

## 테스트 방법

```bash
python3 scripts/demo/reproduce_fresh.py
```

이 harness는 GitHub hosted 실행기에서만 실행한다. 일반 사용자는 README의 `scripts/demo.py`를 사용한다.

```text
그림 1 — 실제 서비스와 검증 경로

빈 컨테이너·볼륨 확인 → start
  ├─ PostgreSQL·Valkey·BGE-M3 준비
  ├─ Gradle 백엔드 → 실제 readiness·listener 확인
  └─ 프런트엔드 → API 프록시 대조
                           │
verify → 프런트엔드 프록시 ─┴→ 가입·업로드·인덱싱·검색·원본 다운로드
  └─ 같은 계정 재실행 → 이전 원장과 문서 ID 비교
Chromium → 로그인 UI → 질문 전송 → 근거 문서 → 다운로드 해시
```

검증기는 실제 HTTP 프록시와 모델을 사용한다. search 범위의 생성 답변은 미실행이며, 제어 모델로 대신 성공시키지 않는다.

```text
그림 2 — 분리 JVM의 소유권 점검

통합 명령 → supervisor 전용 그룹 → Gradle launcher
                                  └─ 단발성 JVM: 별도 그룹 가능
                                       └─ Spring Boot JVM·실제 API 포트

관측된 부모 계보 → PID·시작 시각·명령 fingerprint 등록
준비 판정 → 실제 listener가 소유 그룹 또는 등록된 PID인가?
종료 → 계보 재조회 → 일치하는 자식 PID만 종료 → 그룹·포트 해제 확인
```

그룹 ID 하나에 의존하던 가정은 실제 Gradle 점검에서 성립하지 않았다. 확인된 계보와 개별 식별정보를 사용하도록 보완했다.

## 관측 결과

전체 회차 [39105569049](https://github.com/DocGrid/docgrid/actions/runs/39105569049)가 통과했다.
실행 소스 head는 `dbf782c`, 실행기가 체크아웃한 PR 병합 미리보기는 `a62175ee3e4621c6411feb3af5f4ed3663802b6c`다.
정상 브라우저 컨텍스트 1개와 JavaScript 비활성 SSR 경계 컨텍스트 1개를 사용했다.

| 목적 | 명령·방법 | 실제 결과 | 근거 |
| --- | --- | --- | --- |
| 최초 기동 | `scripts/demo.py start --timeout 1200` | 196.632초, 코드 0 | [로그](runs/39105569049-1/cold-start.log) |
| 첫 시연 | `scripts/demo.py verify --timeout 900` | 24.135초, 업로드·INDEXED 15/15, 검색 3/3, 해시 15/15 | [원장](runs/39105569049-1/first-result/summary.json) |
| 같은 계정 재실행 | 같은 verify | 3.072초, 문서 ID 15/15 일치, 필수 단계 통과 | [원장](runs/39105569049-1/replay-result/summary.json) |
| SSR 인증 경계 | 실제 Chromium, JavaScript 비활성 | POST fallback·초기 제출 비활성 모두 확인 | [별도 판정](runs/39105569049-1/browser/ssr-auth.json) |
| 사용자 경로 | 실제 Chromium 로그인→질문→문서→다운로드 | 3.782초, 세 기능 통과, 원본 52,683 bytes SHA-256 일치 | [판정](runs/39105569049-1/browser/summary.json), [화면](runs/39105569049-1/browser/search.png) |
| 실제 준비·중복 기동 | status·활성 세션 start | 모두 코드 0, 기존 세션 재사용 | [상태](runs/39105569049-1/ready-status.log), [재사용](runs/39105569049-1/reuse-start.log) |
| 중복 start | 첫 기동 중 두 번째 start | 코드 1, 기대한 거부 | [로그](runs/39105569049-1/concurrent-start.log) |
| 준비 부족 | 닫힌 임베딩 주소로 start | 코드 1, 정리 뒤 포트·컨테이너 중지 확인 | [로그](runs/39105569049-1/missing-provider.log) |
| 기동 도중 중단 | backend 프로세스 등록 직후 부모에 SIGTERM | 코드 130, 정리 확인 | [로그](runs/39105569049-1/interrupted-start.log) |
| 캐시 재기동 | stop 뒤 start | 26.061초, 코드 0 | [로그](runs/39105569049-1/warm-start.log) |
| 데이터 보존 | 재기동 뒤 verify | 3.626초, 필수 단계·문서 ID 15/15 통과 | [원장](runs/39105569049-1/warm-result/summary.json) |
| 종료·환경 보존 | stop·재실행·최종 상태 조회 | 소유 컨테이너 3개 중지, 포트 6개 해제, 환경 파일 2개 bytes·mtime 유지 | [전체 원장](runs/39105569049-1/summary.json) |

전체 원장 판정은 24/24 PASS다. 이는 서로 독립인 장애 시험 24회라는 뜻이 아니라, 한 실행기의 여러 명령·관측 항목이다.
준비 부족 코드 1과 취소 코드 130은 각각 기대값과 일치해 시험 판정이 PASS이며, 제품 명령 자체가 성공 코드 0을 반환했다는 뜻은 아니다.
시간은 이 실행기의 명령 wall time이다. 모델 다운로드 단독 시간·운영 RTO·서로 다른 환경의 성능 우위로 해석하지 않는다.
생성 답변·인용 단계는 모든 search 원장에서 NOT_RUN이다.

```text
그림 3 — 이번 회차의 상태와 보존 확인

빈 컨테이너·볼륨 → 첫 start 196.632초 → READY
  → 첫 verify: INDEXED 15 / 기대 검색 3 / 해시 15 통과
  → 같은 계정 verify: 기존 문서 ID 15개 유지
  → Chromium: 로그인·검색·원본 해시 확인
  → stop: 컨테이너 3개 중지·포트 6개 해제
  → 준비 부족 start: 코드 1 → 정리 확인
  → 중단 start: 코드 130 → 정리 확인
  → 캐시 start 26.061초 → verify: 문서 ID·해시 유지
  → 최종 stop → 환경 파일 2개 bytes·mtime 유지
```

아래는 준비·진단 회차이며, 최종 통과 회차와 합쳐 성공으로 계산하지 않는다.

| 실행 ID | 목적·장소 | 실제 관측 | 판정 |
| --- | --- | --- | --- |
| 39103167964 | GitHub workflow 준비 | job 시작 전 설정 오류, 서비스 시험 0회 | 미실행 |
| [39103254726-1](runs/39103254726-1/summary.json) | 첫 독립 환경 회차 | 컨테이너 0·볼륨 0·환경 파일 0, 기존 이미지 6개. 동시 start는 기대한 코드 1로 거부 | 수동 중단, 전체 E2E 미완료 |
| [39104137120-1](runs/39104137120-1/summary.json) | Gradle 보완 뒤 실제 기동 | PostgreSQL·Valkey·BGE-M3 준비와 백엔드·프런트엔드 기동 로그 보존. 프런트엔드 준비 판정 대기 | 주소 바인딩 검토를 위해 수동 중단 |
| [39104533807-1](runs/39104533807-1/summary.json) | IPv4 명시 뒤 실제 모델·브라우저 회차 | 기동 265.870초, 첫 verify 35.680초, 재실행 3.527초. 인덱싱 15/15·검색 3/3·해시 15/15·문서 ID 15/15. Chromium 로그인 단계 timeout | 기능 API 검사 통과, 전체 회차 미완료 |
| [39105008958-1](runs/39105008958-1/summary.json) | 브라우저 세부 진단 | 기동 203.438초·verify 29.690초·재실행 2.475초. 로그인 버튼 클릭 뒤 기본 GET 경로로 이동해 search 이동을 45초 동안 확인하지 못함 | API 단계 통과, 브라우저 불통과 |
| [demo-gradle-process-01](demo-gradle-process-01.log) | macOS 실제 Gradle | 도움말 빌드 코드 0, 직접 자식 JVM의 그룹이 분리됨 | 기존 그룹 가정 검사 불통과 |
| [demo-gradle-process-02](demo-gradle-process-02.log) | macOS 실제 Gradle | 도움말 빌드 코드 0, 분리 자식 1개 등록 | 보완 동작 통과 |
| [demo-runtime-contract-03](demo-runtime-contract-03.log) | macOS 제어 프로세스 회귀 | 75개 중 74개 통과·1개 오류 | 부모 자연 종료 경합 확인 |
| [demo-runtime-contract-04](demo-runtime-contract-04.log) | 같은 회귀 재실행 | 75개 중 74개 통과·1개 불통과 | 종료 시점의 오래된 그룹 snapshot 확인 |
| [demo-runtime-contract-05](demo-runtime-contract-05.log) | 종료 직전 계보 재조회 반영 | 75/75, 7.293초 | 통과 |
| [demo-runtime-contract-06](demo-runtime-contract-06.log) | 미등록 쿼리 비밀값 마스킹 회귀 | 76/76, 9.168초 | 통과 |

## 검증 과정에서 확인된 사항

- job 수준 env에는 runner 컨텍스트를 사용할 수 없다. step env로 이동 후 실제 job 시작을 확인했다.
  [GitHub 컨텍스트 범위](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability).
- 첫 실제 회차는 별도 Gradle 점검의 발견에 따라 중단했다. 성공한 E2E로 계산하지 않는다.
  취소 전 요약·동시 기동 로그는 보존됐지만 구성요소 전체 로그와 최종 정리 원장은 수집되지 않았다.
  이후 실행은 이미 정제된 구성요소 로그를 2초 주기로 artifact 경로에도 복사해 취소 시 손실을 줄인다.
- 분리 자식을 종료하면 부모도 곧바로 종료될 수 있다. 이전 snapshot에만 의존하지 않고 현재 계보와 그룹 해제를 확인한다.
- 프런트엔드 기본 localhost 바인딩은 IPv4 검증 주소와 다를 수 있어 `--hostname 127.0.0.1`을 명시했다.
  원래 회차의 실제 소켓 주소는 수집하지 않았으므로 IPv6가 유일한 원인이었다고 단정하지 않는다.
- 첫 완료된 API 검사 회차는 브라우저 로그인 단계에서 48.381초 뒤 timeout으로 끝났다. 로그인 세부 동작이
  나뉘지 않은 기존 진단으로는 원인을 확정하지 못했다. 이후 입력값·토큰을 제거한 오류와 마스킹 화면을 수집한다.
- 세부 진단에서는 React onSubmit 연결 전 기본 GET 제출로 로그인 URL에 필드가 붙는 것을 확인했다.
  AuthPage는 method=post를 명시하고, 클라이언트 준비 전에는 제출 버튼을 비활성화하도록 보완했다.
  실제 SSR HTML 회귀와 JavaScript 비활성 Chromium 화면에서 이 경계를 별도 검사한다.
  기존 수집 파일에서는 원문 비밀번호 쿼리 파일 0개를 확인했다. 진단 메시지의 비밀번호는 기록 전에 제거됐으며,
  이후 출력 주체가 비밀번호를 모르는 경우에도 password/token/email 쿼리를 일반 마스킹한다.
- npm ci는 종료 코드 0이었지만 기존 의존성 감사 경고 30건(low 1·moderate 6·high 23)을 출력했다.
  이번 변경에서 프런트엔드 package.json·lock 파일을 수정하지 않았다. 감사 경고 해소를 검증했다고 주장하지 않는다.
- 최초 기동 시간에는 npm·Gradle·이미지·모델 준비를 포함한다. 모델 다운로드 시간만 별도 측정했다고 표현하지 않는다.
- 같은 실행기의 캐시 재기동은 최초 실행과 구분한다. 컨테이너·볼륨이 이미 존재한 환경을 새 환경으로 세지 않는다.
- 계정 비밀번호·서명 키·인증 토큰·이메일은 기록 전에 제거한다. 브라우저 trace와 로그인 입력 화면은 저장하지 않는다.
- 실제 OpenSQL·OpenProxy HA, GCP, 실제 Ollama 답변·인용, Windows는 검증 범위 밖이다.
