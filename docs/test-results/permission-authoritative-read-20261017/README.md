# 최신 권한 원장 기반 최종 인가 검증

## 결과

로컬 PostgreSQL 17.8·물리 Standby 2대와 실제 Spring Controller·Service·JPA·Hikari에서 **계약 시험 10개 + 실제 standby 거부 1개, 총 11개가 통과**했습니다. 관련 기존 단위 회귀는 **93개, 실패 0개**입니다.

| 실행 | 결과 | 원인·해석 |
| --- | --- | --- |
| [auth-run01](auth-run01.log) | 9개 중 1개 실패 | 시험 GET URL의 `/api` 누락을 수정 |
| [auth-run02](auth-run02.log) | 9개 중 1개 실패 | 시험 Accept 누락으로 XML 응답을 JSON으로 읽은 오류 수정 |
| [auth-run03](auth-run03.log) | 9개 실패·standby 미실행 | Docker internal 네트워크의 호스트 포트 비공개로 접속 실패. 루프백 게시 전용 bridge로 수정 |
| [auth-run04](auth-run04.log) | **10 + 1개 통과** | 신규·회귀·실제 standby 검증 |
| [auth-unit-run01](auth-unit-run01.log) · [집계](auth-unit-run01-summary.json) | **93개 통과** | 상세·검색·권한·Controller 기존 회귀 |

- 회수 204 뒤 권한 원장 0건·오래된 직접 USER 캐시 1건을 주입: **상세·본문·파일 각각 403**, 새 검색 응답 **200·결과 0건·금지 내용 0건**. 파일 저장소는 호출되지 않았습니다.
- 검색 후보와 모델·검색 접수 원장은 제어 가능한 mock입니다. 실제 SearchController·SearchFacade의 최종 필터와 실제 Primary 권한 원장을 실행했으며 실제 Ollama·pgvector 검색 품질 시험이라고 주장하지 않습니다.
- 실제 물리 standby에 잘못 연결된 최종 인가는 **PERM-003·503**으로 닫히며 내용 반환 0건입니다.
- 현재 USER READ 원장은 허용, 캐시의 위조 ADMIN 비트는 거부, 소유자·PUBLIC은 허용했습니다.
- 다른 유효한 컬렉션 USER 원장은 허용했으며, 원장 만료나 문서 연결 제거 후 오래된 캐시는 거부했습니다.
- 동기 COMMIT 취소·WAL 재주장·같은 키 경합·네트워크 대기 제한 시험도 함께 통과했습니다.

## 목적별 이벤트

- [회수 뒤 오래된 USER 캐시는 상세·본문·파일·새 검색의 최종 응답을 허용하지 않는다](auth-run04--baselineStaleCache.log)
- [동기 후보 전체 부재의 COMMIT 대기는 5초 제한 뒤 결과 불명으로 끝난다](auth-run04--commitTimeoutIsUnconfirmed.log)
- [응답 유실 뒤 같은 키 회수는 새 WAL로 204를 다시 확정한다](auth-run04--baselineLostResponse.log)
- [권한 없는 대상자의 회수는 403이며 요청 원장 INSERT도 롤백된다](auth-run04--unauthorizedRollback.log)
- [별도 컬렉션 USER 권한은 유지하지만 만료·문서 연결 제거 뒤에는 오래된 캐시를 거부한다](auth-run04--collectionProvenanceRemainsLive.log)
- [같은 요청 키를 다른 권한에 사용하면 409이며 새 권한은 유지된다](auth-run04--requestBindingConflict.log)
- [동기 COMMIT 취소는 503이며 같은 키 WAL 재주장으로 204를 재확정한다](auth-run04--baselineCommitCancellation.log)
- [유효한 원장과 PUBLIC·소유자 접근은 유지하고 캐시의 위조된 관리 비트는 거부한다](auth-run04--liveGrantAndAlternateAccessRemainValid.log)
- [동기 후보 설정이 없으면 503이며 권한과 요청 원장을 바꾸지 않는다](auth-run04--missingSynchronousConfiguration.log)
- [같은 키의 동시 회수는 행 잠금으로 직렬화되고 요청 원장 하나로 수렴한다](auth-run04--concurrentReassertion.log)
- [실제 standby에 도착한 최종 인가는 503으로 닫히고 권한 조회를 허용하지 않는다](auth-run04--rejectsActualStandby.log)

## 경계와 한계

공개 HTTP 인증·실제 모델·GCS·운영 OpenProxy·자동 승격은 이 실행에 포함하지 않았습니다. 회수 응답 뒤 시작하는 새 상세·본문·파일·검색 응답을 대상으로 하며, 과거 대화/RAG 조회·이미 진행 중인 요청·문서 목록의 후보 필터는 범위 밖입니다.

최종 인가 wrapper는 read-write Transaction에서 Primary 역할을 먼저 확인합니다. 상세·본문·파일 Snapshot 등의 메서드도 read-write로 명시해 동일 연결을 사용합니다. 권한 조회 전용 wrapper가 기존 외부 read-only Transaction 안에 잘못 사용돼 standby로 라우팅되면 접근을 허용하지 않습니다. 캐시는 권한 출처의 현재 대상·접근 비트·만료·컬렉션 연결까지 교차 검증합니다.

재실행은 `permission_isolated_fixture.py up` → `permissionStandbyTest` → `permission_isolated_fixture.py down`입니다. Standby 시험 task는 먼저 Primary 계약 시험을 실행해 스키마를 준비합니다. 모든 게시 포트는 루프백이며 실제 인프라 식별자·인증 정보는 증거에 포함하지 않습니다.
