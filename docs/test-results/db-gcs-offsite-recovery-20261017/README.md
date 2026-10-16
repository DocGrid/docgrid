# 외부 보존 OpenSQL·GCS 원본을 이용한 격리 복원 검증

## 판정

2026-10-17 KST, 시험용 OpenSQL 클러스터와 비공개 GCS 보존본을 대상으로 **외부에 연속 보존된 기준 백업·WAL과 문서 원본이 실제로 함께 읽히는지** 시험했다. 기준 소스는 `develop`의 `5d9042ae3ef7e9c2c0b183f5d9f669480a76a0f7`이다. 원래 DB·원본 GCS 버킷은 복원 대상으로 사용하지 않았다.

| 항목 | 관측 결과 | 판정 |
| --- | --- | --- |
| Barman 외부 사본 | 완료 기준 백업 1건, 외부 export 136,919,040바이트의 크기·CRC32C·파일명 SHA-256 접두사 3/3 일치, WAL 세그먼트 5개와 history 4개 사용 | 목표 시점의 복원 입력 확인 |
| 격리 PITR | 목표 timeline 20, 2026-10-16 22:32:35 KST. 별도 데이터 경로에서 실행 후 시험 마커 A 1건·B 0건 | 선택한 시점까지 복구 성공 |
| DB·GCS 참조 | 격리 DB의 GCS 파일 93건·문서 버전 참조 99건. 비공개 보존 감사 원장과 비교한 누락·크기·SHA·generation 불일치 각 0건 | 해당 시점의 참조 대조 통과 |
| 실제 사용자 다운로드 | 보존본 경로로 93건을 격리 DB에서만 전환. 별도 백엔드 readiness 200, 시험 계정 로그인 200, 기존 문서 다운로드 200·114바이트·DB 크기/해시 일치 | 기존 문서 1건의 HTTP 경로 통과 |
| 원복 | 원래 리더 1대·streaming Standby 2대, 지연 0·0바이트. Barman 수신 관련 3/3 OK. 격리 데이터·timer·임시 키 제거 | 시험 환경 원복 통과 |

**검증 범위는 선택한 PITR 시점의 기존 문서 다운로드까지다.** 같은 회차에서 새 업로드, 미완료 Job 재개, 실제 임베딩·검색·RAG 답변은 실행하지 않았다. 따라서 “전체 서비스 복구”, 운영 RPO 0, 단일 RTO를 주장하지 않는다.

## 실행 경계와 절차

```text
별도 GCS DB 백업 저장소            별도 GCS 문서 원본 보존 저장소
  ├─ 완료 표시가 가리키는 export       └─ 현재 원본·보존본 비공개 감사 원장
  └─ 완료 WAL 5개 + history 4개                    │
               │                                  │
               ▼                                  │
     백업 VM의 별도 Barman 카탈로그                 │
       import → verify → recover                   │
               │                                  │
               ▼                                  │
     시험 Standby의 별도 PGDATA                    │
       localhost 전용 PITR OpenSQL                 │
               │   file_objects 93건 ↔ 감사 원장 ──┘
               ▼
     격리 DB의 bucket·key 93건만 보존본으로 전환
               │
               ▼
     로컬 별도 백엔드 → IAP SSH localhost 터널
               └─ 인증 → 기존 문서 GET /file → 114바이트·SHA 대조
```

원래 Standby 한 대를 시험 동안 잠시 제외했으므로 **30분 호스트 로컬 자동 원복 timer를 먼저 설치**했다. 시험 뒤에는 timer를 기다리지 않고 수동 원복했으며, 리더·두 Standby·WAL 수신을 재확인한 다음 시험 경로와 timer를 제거했다.

복원 바이트는 외부 보존본을 사용했지만, 빈 카탈로그를 준비할 때 **살아 있는 시험 DB의 systemid를 조회해 export의 식별자와 대조**했고, 기존 시험 Standby의 허가된 OpenSQL 바이너리를 사용했다. 따라서 이 실행은 “백업 VM과 원래 클러스터가 모두 사라진 상태에서 신규 호스트로 복원”한 시험이 아니다.

## 실패와 수정 내역

1. 로컬 CLI 인증·샌드박스 문제로 첫 자산 조사는 샘플 0건이었다. 별도 회차에서 다시 조회했다. [첫 조회](inventory-r1.md), [재조회](inventory-r2.md).
2. 새 빈 Barman 카탈로그의 `import-backup`이 두 번 거절됐다. 설치된 Barman은 tar의 identity를 읽기 전에 **대상 카탈로그의 `identity.json`을 먼저 요구**했다. export의 identity와 시험 DB systemid를 대조한 뒤 격리 카탈로그에만 식별 파일을 준비해 import했다. 이후 `verify-backup`에는 격리 설정의 PostgreSQL 도구 `path_prefix`가 필요했다. [첫 import](import-r1.md), [재시도](import-r2.md), [원인](import-diagnose-r5.md), [성공](import-r3.md), [검증](verify-r1.md).
3. 첫 HTTP 회차는 `start-iap-tunnel`의 로컬 포트가 열리지 않아 앱을 실행하지 않았다. IAP SSH의 localhost `-L` 전달로 바꾼 두 번째 회차에서 다운로드를 검증했다. [첫 회차](http-download-r1.md), [재시험](http-download-r2.md).

실패 회차의 결과를 성공 회차와 합산하지 않았다. 원시 CLI 출력에는 비공개 경로·객체 식별자가 포함될 수 있어, 저장소에는 실행 전 계획과 안전한 집계 결과만 남겼다.

## 개별 실행 기록

| 구간 | 기록 | 핵심 결과 |
| --- | --- | --- |
| 사전 확인 | [계획](plan.md), [토폴로지](topology-r1.md), [외부 사본 범위](coverage-r2.md), [Import 사전 점검](import-preflight-r1.md) | 리더 1·Standby 2, 외부 기준 백업·WAL 확인 |
| 외부 DB 입력 | [Import 성공](import-r3.md), [Barman 검증](verify-r1.md), [PITR 파일 준비](recover-files-r1.md), [Standby 전송](transfer-r1.md) | 별도 백업 1건·전송 파일 1,904개 동일 |
| 격리 DB 실행 | [전환 전 점검](cutover-preflight-r1.md), [자동 원복 설치](rollback-arm-r1.md), [PITR 기동](pitr-runtime-r1.md) | 목표 마커 A 1·B 0 |
| 문서 원본 | [GCS 감사](gcs-audit-r1.md), [DB 참조 대조](db-gcs-reference-r1.md), [격리 DB 경로 전환](rebind-r1.md) | 93/93건 참조·보존본 원장 일치 |
| 사용자 경로 | [HTTP 사전 점검](http-preflight-r1.md), [실행 자원 점검](runtime-capability-r1.md), [다운로드 재시험](http-download-r2.md) | HTTP 200, 114바이트·SHA 일치 |
| 원복 | [수동 원복](cleanup-r1.md), [3노드·WAL 수신](post-restore-health-r1.md), [Standby 경로 정리](cleanup-stage-r1.md), [백업 VM stage 정리](cleanup-backup-stage-r1.md) | 1+2 복귀, Barman 3/3 OK, 시험 경로 제거 |

## 해석과 남은 검증

- GCS 감사는 앞선 전체 바이트 검사 결과를 보유한 **비공개 증분 원장**을 이번에 다시 확인했다. 이번 감사에서 객체 generation 변경이 없어 새 다운로드는 0건이었다. DB 파일 93건의 크기·SHA는 그 원장과 대조했고, 사용자 HTTP 다운로드에서 1건의 실제 보존 객체 바이트를 다시 읽어 해시를 확인했다. **93건을 이번 실행에서 전부 다시 내려받아 해시한 것은 아니다.**
- 선택한 과거 시점의 마커와 문서 참조는 맞았지만, 비동기 GCS 복사가 끝나기 전 원본이 사라지는 경우까지 보호하지는 못한다. 성공 응답 원장 전체와 최신 복원 가능 시점을 대조하지 않아 운영 RPO를 측정하지 않았다.
- 복원 준비에는 import 실패 분석, 설정 보완, 터널 재시도가 포함됐다. 최초 조사부터 HTTP 성공까지의 경과 시간을 정식 RTO로 사용하지 않는다.
- 실제 사용자 기능의 다음 검증은 **격리 환경에서 새 업로드·미완료 Job 재개·검색을 한 실행 ID로 연결**하고, 모델 또는 명시된 스텁 조건과 정리 가능한 시험 저장소를 먼저 준비한 뒤 실시해야 한다. 30일 최소 보존 버킷에 합성 업로드를 남기는 시험은 이번 회차에 추가하지 않았다.
