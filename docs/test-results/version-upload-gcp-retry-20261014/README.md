# 새 버전 업로드 동일 키 재시도: GCP 배포·장애 시험

2026-10-14 KST. 기준 커밋은 [`eb779ace`](https://github.com/DocGrid/docgrid/commit/eb779acef4f0452446b4062cf583b6f62e4d4d0b)이다. 이 문서는 병합된 새 버전 업로드 백엔드를 GCP 앱 A/B에 배포한 뒤 실행한 **서로 다른 세 회차**의 결과다. 시험에 사용한 PDF와 계정은 합성 데이터이며, 각 회차의 원본 단계 기록은 `runs/`에 별도로 보관했다. 비밀값, 계정, 요청 키, 내부 주소, 클라우드 프로젝트·버킷·객체 식별자는 기록하지 않았다.

## 목적·실행 경계

같은 문서에 대한 새 버전 업로드를 동일한 `Idempotency-Key`·동일한 파일로 재전송할 때 버전과 Job이 한 번만 추가되는지 확인한다. 같은 키에 다른 파일을 보내면 409가 나와야 한다. DB의 버전·Job·완료 요청 원장과 GCS 실제 객체 바이트를 독립적으로 대조한다.

시험 클라이언트는 운영자 컴퓨터의 **IAP 터널 → GCP 앱 A/B**로 직접 요청했다. 실제 OpenSQL과 GCS는 사용했지만, **Vercel·공개 게이트웨이·내부 LB와 인덱싱 Worker는 통과하지 않았다.** Worker가 꺼져 있으므로 시험용 v1의 DB 상태만 해당 합성 문서에서 `INDEXED`로 조정한 뒤 v2 API를 호출했다. 이는 v2 진입 조건을 만들기 위한 준비이며, v1이 실제로 인덱싱됐다는 뜻은 아니다. 각 회차는 별도 합성 계정·문서·요청 키를 사용했다.

```text
그림 1 — 이번 시험의 실제 경로와 생략된 경로

합성 시험 클라이언트 ── IAP 터널 ──▶ 앱 A/B ──▶ OpenProxy ──▶ OpenSQL
                                    │                         │
                                    └──────────────▶ GCS 원본  │
                                                              └─ 버전·Job·요청 원장

Vercel UI ── 공개 게이트웨이 ── 내부 LB : 이번 세 회차에서 사용하지 않음
Worker ── 임베딩 ── 활성 청크 ── 검색 : 이번 세 회차에서 사용하지 않음
```

## 사전 검증과 배포

| 실행 | 위치·방법 | 관측 결과 | 판정 |
| --- | --- | --- | --- |
| [`RELEASE-BUILD-20261014-01`](runs/RELEASE-BUILD-20261014-01.safe.log) | 로컬 격리 소스, Gradle offline | Gradle 캐시 접근 권한 오류로 테스트 전 중단 | 실패, 앱 코드 판정 불가 |
| [`RELEASE-BUILD-20261014-02`](runs/RELEASE-BUILD-20261014-02.safe.log) | 로컬 격리 소스, Gradle offline | JAR 생성 뒤 테스트 DB 연결 실패 | 실패, DB 준비 필요 |
| [`RELEASE-DBTEST-20261014-01`](runs/RELEASE-DBTEST-20261014-01.safe.log) | 임시 PostgreSQL 17/pgvector | 1,450건 중 실패 66건, JWT 설정·Redis 미준비 | 실패, 의존성 준비 필요 |
| [`RELEASE-DBREDIS-20261014-01`](runs/RELEASE-DBREDIS-20261014-01.safe.log) | 임시 PostgreSQL·Redis·JWT | 1,421건 중 실패 22건, DB 연결 한도 초과 | 실패, DB 연결 한도 조정 필요 |
| [`RELEASE-DBREDIS-20261014-02`](runs/RELEASE-DBREDIS-20261014-02.safe.log) | 임시 PostgreSQL 17/pgvector(`max_connections=300`)·Redis·JWT, `./backend/gradlew -p backend test bootJar --offline --no-daemon --max-workers=1` | **1,471건, 실패 0건, 건너뜀 5건, Gradle 종료 0** | 통과. 임시 컨테이너 제거 |
| [`CLOUD-DEPLOY-20261014-01`](runs/CLOUD-DEPLOY-20261014-01.safe.log) | 로컬 GCP CLI → 앱 A/B | 검증 JAR과 복구 가드만 준비, 각 전송 종료 0 | 통과 |
| [`CLOUD-TUNNEL-20261014-01`](runs/CLOUD-TUNNEL-20261014-01.safe.log) | 로컬 IAP 터널 → 앱 A/B | 무인증 `/auth/me`는 양쪽 모두 401, 시험 종료 후 터널 2개 닫음 | 시험 경로 준비·정리 통과 |
| [`CLOUD-DEPLOY-FINALIZE-20261014-01`](runs/CLOUD-DEPLOY-FINALIZE-20261014-01.safe.log) | GCP 앱 A/B | 순차 배포 뒤 양쪽 JAR SHA-256 앞 16자리 `f39da6ca5cddcb0f`, readiness 200, 자동 원복 타이머 해제 | 통과 |

배포 JAR 전체 SHA-256은 `f39da6ca5cddcb0f7e149a2ad5f0eb24df70d75b744598149384ddeaeb2206ae`이며 Flyway 최종 버전은 **49**다. 앞선 실패 회차도 성공한 시험으로 합치지 않았다.

## 새 버전 API 세 회차

| 실행 | 장애·HTTP 관측 | DB·GCS 독립 대조 | 판정 |
| --- | --- | --- | --- |
| [`VERSION-GCP-BASELINE-20261014-01`](runs/VERSION-GCP-BASELINE-20261014-01.safe.log) | Patroni 리더 명칭을 시험 하네스가 잘못 해석하여 **요청 전 종료** | 업로드 없음 | 실패한 준비 회차, 성과 제외 |
| [`VERSION-GCP-BASELINE-20261014-02`](runs/VERSION-GCP-BASELINE-20261014-02.safe.log) | 정상 경로: v2 최초 201, 앱 B 동일 키 재전송 201, 다른 파일 409. 재전송 응답의 **5개 필드 일치** | v1·v2 합계: 버전 **2**, Job **2**, 완료된 v2 요청 **1**. DB 파일 2개와 GCS 객체 2개 모두 바이트 SHA-256 일치 | 통과 |
| [`VERSION-GCP-PROXY-20261014-01`](runs/VERSION-GCP-PROXY-20261014-01.safe.log) | OpenProxy A 프로세스 중단 중 v2 최초 요청 201, 요청 뒤에도 A 중단 확인. A 복구 후 앱 B 동일 키 재전송 201·5개 필드 일치, 다른 파일 409 | 버전 **2**, Job **2**, 완료된 v2 요청 **1**. DB 파일 2개와 GCS 객체 2개 모두 바이트 SHA-256 일치 | **장애 구간 겹침과 중복 방지 통과**. 실패 응답 재시도는 미관측 |
| [`VERSION-GCP-RESPONSE-DROP-20261014-01`](runs/VERSION-GCP-RESPONSE-DROP-20261014-01.safe.log) | v2 요청 본문 **1,157바이트**를 보낸 직후 응답을 읽지 않고 연결 종료. 재전송 전에 DB 완료 상태 확인. 앱 B 동일 키 재전송 201·커밋 원장과 5개 필드 일치, 다른 파일 409 | **재전송 전·후 모두** 버전 **2**, Job **2**, 완료된 v2 요청 **1**. DB 파일 2개와 GCS 객체 2개 모두 바이트 SHA-256 일치 | **결과 불명 재전송 통과**. 서버 측 HTTP 응답 상태는 관측 불가 |

```text
그림 2 — 정상 회차의 동일 키·다른 입력 판정

시험 전: 합성 v1 문서 1 · 버전 1 · Job 1
앱 A: v2 파일 F + 키 K ──▶ HTTP 201, versionNo=2
앱 B: v2 파일 F + 키 K ──▶ HTTP 201, 동일한 응답 5필드
앱 B: 다른 파일 G + 키 K ──▶ HTTP 409
시험 후: 버전 2 · Job 2 · 완료된 v2 요청 1
         DB 파일 2 ↔ GCS 객체 2, 실제 바이트 해시 2/2 일치
```

```text
그림 3 — OpenProxy A 장애 회차의 시간 경계

14:39:33 KST  호스트 복구 가드 무장(150초)
14:39:47 KST  OpenProxy A 중단 확인
14:39:48 KST  앱 A에 v2 업로드 → HTTP 201
14:39:55 KST  첫 요청 뒤에도 OpenProxy A 중단 확인
14:40:17 KST  OpenProxy A 복구·가드 해제
14:40:17 KST  앱 B에 같은 키 재전송 → HTTP 201, 같은 결과
14:40:18 KST  같은 키·다른 파일 → HTTP 409
14:40:36 KST  DB 원장 2|2|1, GCS 원본 해시 2/2 일치
```

첫 요청이 프록시 장애와 **시간상 겹쳤다는 것**은 확인했다. 그러나 그 요청은 201이었으므로 **HTTP 500·503·시간 초과 뒤 복구**를 증명하지 않는다. 별도 응답 유실 회차는 클라이언트가 본문을 보낸 후 응답을 읽지 않은 상태에서 서버 커밋을 재전송 전에 확인했다. 이 회차는 **프록시 장애를 동시에 주입하지 않았으며**, 서버가 원래 보내려던 HTTP 상태도 관측하지 못했다.

```text
그림 4 — 응답을 모르는 v2 업로드의 재전송 판정

앱 A: v2 파일 F + 키 K 전송 완료 ──▶ 응답 읽기 전에 클라이언트 연결 종료
       사용자 관측 HTTP 상태 = 없음
             │
             └─ 재전송 전에 OpenSQL 조회: 버전 2 · Job 2 · 완료된 v2 요청 1
                원장에 저장된 versionId·versionNo·jobId·currentVersionId 확인

앱 B: 같은 파일 F + 같은 키 K ──▶ HTTP 201
       원장 결과와 documentId를 포함한 5개 필드 일치
앱 B: 다른 파일 G + 같은 키 K ──▶ HTTP 409
       재전송 후 OpenSQL 2|2|1 유지, DB 파일↔GCS 바이트 해시 2/2 일치
```

## 복구·배포 상태와 미검증 범위

[`CLOUD-RECOVERY-20261014-01`](runs/CLOUD-RECOVERY-20261014-01.safe.log)에서 앱 A/B 모두 새 JAR·readiness 200, OpenProxy A/B 각각 ready, OpenSQL Leader 1·streaming Replica 2(지연 0), etcd 3/3 healthy를 확인했다. 이후 앱의 자동 원복 타이머를 해제하고 시험용 IAP 터널을 종료했다. 응답 유실 회차에 새로 연 시험용 터널 2개와 전용 OS Login SSH 키도 종료·삭제했다. 기존 기본 OS Login 공개키는 유효기간을 1시간으로 갱신했으며 자동 만료 예정이다. 이전 JAR 백업은 앱에 남겼다. 합성 계정·문서·GCS 객체·대기 Job은 증거 보존을 위해 남아 있으며 Worker가 꺼져 있어 실제 인덱싱 완료 여부는 확인하지 않았다. DB 참조 밖의 GCS 고아 객체 전체 목록도 조사하지 않았다.

공개 Vercel URL은 로그인 화면까지 열렸지만, [`FRONTEND-PUBLIC-CHECK-20261014-01`](runs/FRONTEND-PUBLIC-CHECK-20261014-01.safe.log)에서 새로 내려받은 `DocGridApp-CSMkgQv7.js` 번들에는 병합 소스의 고유 문구 `이전 버전이 이미 저장됐을 수 있습니다`, `같은 업로드 다시 시도`, `Idempotency-Key`가 모두 없었다. `DocGridApp.tsx`가 `UploadModal.tsx`를 정적 import하므로 **공개 프론트가 #493 기능을 제공한다고 확인할 수 없다.** Vercel 관리 화면은 인증되지 않아 배포 SHA와 자동 배포 설정을 확인하지 못했고, 이번 작업에서 프론트 재배포도 하지 않았다. 따라서 실제 브라우저→공개 API→GCP 전 구간의 새 버전 동일 키 재시도는 **미검증**이다.
