package com.opensource.docgrid.domain.permission.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.PermissionTargetUserResponse;
import com.opensource.docgrid.domain.permission.enums.PermissionTargetType;
import com.opensource.docgrid.domain.permission.enums.PermissionType;
import com.opensource.docgrid.domain.permission.service.command.CollectionPermissionCommandService;
import com.opensource.docgrid.domain.permission.service.command.DocumentPermissionCommandService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.permission.service.query.PermissionTargetUserQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 문서·컬렉션 직접 권한 관리와 권한 대상 사용자 검색 API의 URL·인가 계약을 검증한다.
 */
@WebMvcTest(PermissionController.class)
@DisplayName("PermissionController 테스트")
class PermissionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private com.opensource.docgrid.domain.permission.service.DocumentPermissionRevocationFacade documentPermissionRevocationFacade;

    @MockitoBean private CollectionPermissionCommandService collectionPermissionCommandService;
    @MockitoBean private DocumentPermissionCommandService documentPermissionCommandService;
    @MockitoBean private PermissionQueryService permissionQueryService;
    @MockitoBean private PermissionTargetUserQueryService permissionTargetUserQueryService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("문서 ADMIN 사용자가 문서 직접 권한 목록을 조회한다")
    void getDocumentPermissions_returnsDirectPermissions() throws Exception {
        DocumentPermissionResponse response = new DocumentPermissionResponse(
                100L,
                5L,
                PermissionTargetType.USER,
                20L,
                "대상유저",
                null,
                null,
                null,
                null,
                PermissionType.READ,
                true,
                false,
                false,
                10L,
                "테스트유저",
                LocalDateTime.of(2026, 8, 1, 10, 0),
                null
        );
        given(permissionQueryService.getDocumentPermissions(10L, 5L)).willReturn(List.of(response));

        mockMvc.perform(get("/permissions/documents/5")
                        .with(authentication(authenticationWithUserId(10L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].permissionId").value(100))
                .andExpect(jsonPath("$.data[0].targetType").value("USER"))
                .andExpect(jsonPath("$.data[0].userId").value(20))
                .andExpect(jsonPath("$.data[0].userName").value("대상유저"))
                .andExpect(jsonPath("$.data[0].grantedByName").value("테스트유저"));
    }

    @Test
    @DisplayName("컬렉션 ADMIN 사용자가 컬렉션 직접 권한 목록을 조회한다")
    void getCollectionPermissions_returnsDirectPermissions() throws Exception {
        CollectionPermissionResponse response = new CollectionPermissionResponse(
                200L,
                1L,
                PermissionTargetType.ROLE,
                null,
                null,
                30L,
                "ADMIN",
                null,
                null,
                PermissionType.ADMIN,
                true,
                true,
                true,
                10L,
                "테스트유저",
                LocalDateTime.of(2026, 8, 1, 10, 0),
                null
        );
        given(permissionQueryService.getCollectionPermissions(10L, 1L)).willReturn(List.of(response));

        mockMvc.perform(get("/permissions/collections/1")
                        .with(authentication(authenticationWithUserId(10L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].permissionId").value(200))
                .andExpect(jsonPath("$.data[0].roleId").value(30))
                .andExpect(jsonPath("$.data[0].roleName").value("ADMIN"))
                .andExpect(jsonPath("$.data[0].grantedByName").value("테스트유저"))
                .andExpect(jsonPath("$.data[0].canAdmin").value(true));
    }

    @Test
    @DisplayName("자원 ADMIN 권한이 없으면 403을 반환한다")
    void getDocumentPermissions_returnsForbidden_whenResourceAdminIsDenied() throws Exception {
        given(permissionQueryService.getDocumentPermissions(10L, 5L))
                .willThrow(new DocGridException(ErrorCode.PERMISSION_DENIED));

        mockMvc.perform(get("/permissions/documents/5")
                        .with(authentication(authenticationWithUserId(10L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE-002"));
    }

    @Test
    @DisplayName("문서 ADMIN이 동명이인 구분 정보가 포함된 권한 대상 사용자를 검색한다")
    void searchDocumentPermissionUsers_returnsDisambiguatedUsers() throws Exception {
        given(permissionTargetUserQueryService.searchForDocument(10L, 5L, "김기민"))
                .willReturn(List.of(new PermissionTargetUserResponse(
                        20L,
                        "김기민",
                        "gimin@example.com",
                        3L,
                        "개발팀"
                )));

        mockMvc.perform(get("/permissions/documents/5/users")
                        .param("keyword", "김기민")
                        .with(authentication(authenticationWithUserId(10L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(20))
                .andExpect(jsonPath("$.data[0].name").value("김기민"))
                .andExpect(jsonPath("$.data[0].email").value("gimin@example.com"))
                .andExpect(jsonPath("$.data[0].departmentName").value("개발팀"));
    }

    @Test
    @DisplayName("컬렉션 ADMIN 권한이 없으면 권한 대상 사용자 검색은 403을 반환한다")
    void searchCollectionPermissionUsers_returnsForbidden_whenResourceAdminIsDenied() throws Exception {
        given(permissionTargetUserQueryService.searchForCollection(10L, 7L, "김기민"))
                .willThrow(new DocGridException(ErrorCode.PERMISSION_DENIED));

        mockMvc.perform(get("/permissions/collections/7/users")
                        .param("keyword", "김기민")
                        .with(authentication(authenticationWithUserId(10L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE-002"));
    }

    private UsernamePasswordAuthenticationToken authenticationWithUserId(Long userId) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                "user",
                "password",
                List.of()
        );
        authentication.setDetails(userId);
        return authentication;
    }
}
