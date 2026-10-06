# DEPARTMENT 공개 범위 반영과 컬렉션 USER 권한 부여 오류 수정

- 관련 이슈: 없음 (기능명세서 시험 중 발견해 이슈 없이 바로 수정)
- 작성일: 2026-10-06
- 상태: 구현·테스트 완료

## 1. 배경

2026-10-06 기능명세서의 시험 항목을 실제 서버(OpenSQL 3노드 DB 연결)에서 실행하다가 권한 도메인의 문제 세 가지를 확인했다.

| # | 증상 | 원인 |
|---|---|---|
| 1 | 컬렉션에 **사용자(USER) 대상** 권한을 부여하면 500 오류가 나고 권한이 생기지 않는다. ROLE·DEPARTMENT 대상과 문서 권한은 정상이다. | 부여자를 `getReferenceById`로 읽어 프록시가 되는데, USER 대상만 타는 벌크 UPDATE(`@Modifying(clearAutomatically = true)`)가 영속성 컨텍스트를 비우면서 프록시가 분리된다. 응답 변환의 `getGrantedBy().getName()`이 `LazyInitializationException`을 던진다. 응답에 부여자 이름을 넣은 변경(`c200f80`) 이후 드러난 것으로 보인다. |
| 2 | 업로드 창에서 공개 범위를 `DEPARTMENT · 같은 부서`로 고르면 같은 부서 사용자도 문서를 열 수 없다(403). | `VisibilityType.DEPARTMENT`는 저장만 되고 열람 판정과 검색 SQL 어디에서도 확인하지 않는다. `PUBLIC`만 2단계에서 따로 확인한다. |
| 3 | 업로드 창에 `COLLECTION · 컬렉션 권한` 옵션이 있지만 고르나 안 고르나 결과가 같다. | 의도는 "소속 컬렉션 권한자만"이었으나, 문서 열람 판정의 마지막 단계(부모 컬렉션 권한 상속)가 `PRIVATE` 문서에도 이미 같은 효과를 낸다. 구분할 의미가 없다. |

## 2. 변경 내용

### 2.1 컬렉션 USER 권한 부여 오류 수정

`CollectionPermissionCommandService.grantPermission`에서 부여자를 `findById`로 읽는다. 이미 초기화된 엔티티는 영속성 컨텍스트가 비워져도 `getName()`이 동작한다. 문서 권한은 단건 갱신(`grantUserPermission`)을 써서 영향이 없어 바꾸지 않았다.

### 2.2 DEPARTMENT 공개 범위

공개 범위가 `DEPARTMENT`인 문서는 **소유자와 같은 부서의 사용자**가 **읽기만** 할 수 있다.

| 위치 | 내용 |
|---|---|
| `PermissionQueryService.canReadDocument` | 2단계(`PUBLIC`) 다음에 2-1단계 추가 |
| `PermissionQueryService.checkDocumentPermission` | 같은 규칙, 권한 경로(`sources`)에 `DEPARTMENT_VISIBILITY` 표시 |
| `DocumentRepository.findReadableDocumentIds` | 검색·목록 pre-filter `UNION` 블록 추가 |
| `DocumentRepository.findReadableDocumentIdsInCollection` | 컬렉션 범위 pre-filter에 같은 블록 추가 |
| `DocumentRepository.existsDepartmentVisibleToUser` (신규) | 단건 판정용 쿼리. pre-filter SQL의 새 블록과 조건을 맞춘다 |
| `PermissionSourceType` | `DEPARTMENT_VISIBILITY` 추가 |

- "같은 부서"는 소유자의 **현재** 부서와 사용자의 **현재** 부서가 같은 경우다. 실시간 SQL이라 캐시 무효화가 필요 없고, 부서가 바뀌면 판정도 바로 따라간다.
- 소유자나 사용자의 부서가 없으면 `NULL` 비교가 거짓이라 제외된다.
- 쓰기·관리 권한(`canWriteDocument`, `canAdminDocument`)에는 영향이 없다.
- 삭제된 문서와 요청한 상태(`statuses`) 필터는 기존 블록과 같이 적용된다.

### 2.3 COLLECTION 옵션 제거

`UploadModal.tsx`의 공개 범위 드롭다운에서 `COLLECTION` 옵션을 뺐다. 서버의 enum과 업로드 API 검증은 바꾸지 않았다. 공유 DB에는 `COLLECTION` 행이 0건이지만, 다른 DB 복사본의 행은 확인할 수 없고 `@Enumerated(STRING)`이라 값을 지우면 그 행을 읽는 조회가 실패할 수 있어서다.

## 3. API 영향과 오류 케이스

- 요청 형식과 상태 코드는 바뀌지 않는다.
- `POST /permissions/collections/{id}` (USER 대상): 500 → 201. 응답의 `grantedByName`이 정상으로 채워진다.
- `GET /permissions/documents/{id}/me`: 공개 범위가 `DEPARTMENT`이고 같은 부서면 `canRead: true`, `sources`에 `DEPARTMENT_VISIBILITY`가 포함된다. 응답에 새 enum 값이 나올 수 있으므로 값을 엄격하게 매핑하는 클라이언트는 확인이 필요하다(프런트는 문자열을 그대로 표시한다).
- 공개 범위 `DEPARTMENT` 문서: 같은 부서면 열람·검색 가능, 다른 부서나 부서 없음은 기존처럼 403 / 검색 제외.

| 상황 | 동작 |
|---|---|
| 같은 부서, 공개 범위 `DEPARTMENT` | 읽기 가능 (신규) |
| 다른 부서, 공개 범위 `DEPARTMENT` | 기존과 동일 (403 / 검색 제외) |
| 소유자나 사용자의 부서가 없음 | 제외 |
| 같은 부서, 공개 범위 `PRIVATE` | 기존과 동일 (403) |
| 공개 범위 변경 API | 기존과 동일 (`PRIVATE`/`PUBLIC`만 허용, 그 외 400) |

## 4. 알려진 한계

- 공개 범위 **변경** API와 문서 상세 화면의 토글은 여전히 `PRIVATE`/`PUBLIC`만 지원한다. `DEPARTMENT`는 업로드 때만 고를 수 있고, 올린 뒤에는 바꿀 수 없다. 이번 범위에는 넣지 않았다.
- 이미 `DEPARTMENT`로 올라간 문서(공유 DB 기준 1건)는 이 변경으로 같은 부서 사용자에게 열람된다.
- 컬렉션의 공개 범위는 이전부터 `PRIVATE`/`PUBLIC`만 허용하며 이번에 바꾸지 않았다.

## 5. 검증

| 구분 | 테스트 | 내용 |
|---|---|---|
| 신규 | `CollectionUserPermissionGrantIntegrationTest` (2) | 실제 PostgreSQL에서 USER 대상 컬렉션 권한 부여가 오류 없이 끝나고, 부여자 이름이 응답에 담기며, 컬렉션 문서의 캐시가 생긴다. 빈 컬렉션도 확인한다. 수정을 되돌리면 두 테스트가 `LazyInitializationException`으로 실패하는 것을 확인했다. |
| 신규 | `DocumentDepartmentVisibilityRepositoryTest` (6) | 검색 pre-filter 두 쿼리와 단건 쿼리가 같은 부서만 허용, 다른 부서·부서 없음·`PRIVATE`·삭제 문서 제외, 부서 변경 즉시 반영을 확인한다. |
| 추가 | `PermissionQueryServiceTest` (+4) | 2-1단계 판정, 다음 단계로 넘어가는 경우, `PRIVATE`은 부서 조회를 하지 않는 경우, `/me`의 출처 표시 |
| 추가 | `frontend/tests/rendered-html.test.mjs` (+1) | 업로드 창에 `COLLECTION` 옵션이 없고 `PRIVATE`·`DEPARTMENT`·`PUBLIC`이 있다. |

테스트는 개발·공유 DB와 분리한 임시 PostgreSQL(pgvector 0.8.1)과 Valkey 컨테이너에서 실행했다.

## 6. 범위 밖

- `DOCUMENT_MANAGER` 역할은 하는 일이 없지만 이번에 손대지 않았다(MVP 제외 방침 유지).
- 과거 만료 시각으로도 권한이 부여되는 문제(효과는 없음)는 보류했다.
- 컬렉션 검색 범위가 하위 컬렉션 문서를 포함하지 않는 것은 의도된 동작이다.
