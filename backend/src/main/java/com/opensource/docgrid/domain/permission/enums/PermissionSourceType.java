package com.opensource.docgrid.domain.permission.enums;

/**
 * 문서 권한 확인 API 응답용 — 사용자가 문서에 접근할 수 있는 경로(출처)를 나타낸다.
 */
public enum PermissionSourceType {
    OWNER,
    PUBLIC,
    USER_CACHE,
    ROLE,
    DEPARTMENT,
    DEPARTMENT_VISIBILITY
}
