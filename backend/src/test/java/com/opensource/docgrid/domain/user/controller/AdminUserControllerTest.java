package com.opensource.docgrid.domain.user.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;

import com.opensource.docgrid.domain.auth.jwt.JwtProvider;
import com.opensource.docgrid.domain.auth.jwt.RoleAuthorityService;
import com.opensource.docgrid.domain.auth.jwt.TokenBlacklistService;
import com.opensource.docgrid.domain.mcp.service.command.McpAccessTokenCommandService;
import com.opensource.docgrid.domain.user.dto.request.ChangeDepartmentRequest;
import com.opensource.docgrid.domain.user.dto.response.AdminUserResponse;
import com.opensource.docgrid.domain.user.dto.response.UserRoleResponse;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.service.command.UserCommandService;
import com.opensource.docgrid.domain.user.service.command.UserRoleCommandService;
import com.opensource.docgrid.domain.user.service.query.AdminUserQueryService;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.config.SecurityConfig;
import com.opensource.docgrid.global.exception.RestAccessDeniedHandler;
import com.opensource.docgrid.global.exception.RestAuthenticationEntryPoint;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.jsonwebtoken.Claims;

/**
 * 관리자 사용자 목록 API의 필터·Pagination·민감 정보 비노출과 ADMIN Security 계약을 검증한다.
 */
@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
@DisplayName("AdminUserController 테스트")
class AdminUserControllerTest {

    private static final String USERS_URL = "/admin/users";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private UserRoleCommandService userRoleCommandService;
    @MockitoBean private UserCommandService userCommandService;
    @MockitoBean private AdminUserQueryService adminUserQueryService;
    @MockitoBean private JpaMetamodelMappingContext jpaMetamodelMappingContext;
    @MockitoBean private JwtProvider jwtProvider;
    @MockitoBean private TokenBlacklistService tokenBlacklistService;
    @MockitoBean private RoleAuthorityService roleAuthorityService;
    @MockitoBean private McpAccessTokenCommandService mcpAccessTokenCommandService;
    @MockitoBean private CorsConfigurationSource corsConfigurationSource;

    @Test
    @DisplayName("ADMIN 사용자가 검색·부서·상태 필터로 사용자와 역할을 페이지 조회한다")
    void getUsers_returnsFilteredUserPage() throws Exception {
        AdminUserResponse response = new AdminUserResponse(
                10L,
                "관리자",
                "admin@example.com",
                "admin",
                3L,
                "플랫폼팀",
                UserStatus.ACTIVE,
                List.of("ADMIN", "USER"),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0)
        );
        given(adminUserQueryService.getUsers("admin", 3L, UserStatus.ACTIVE, 0, 20))
                .willReturn(new PageResponse<>(List.of(response), 0, 20, 1, 1, true, true));

        mockMvc.perform(get(USERS_URL)
                        .param("keyword", "admin")
                        .param("departmentId", "3")
                        .param("status", "ACTIVE")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value(10))
                .andExpect(jsonPath("$.data.content[0].email").value("admin@example.com"))
                .andExpect(jsonPath("$.data.content[0].departmentName").value("플랫폼팀"))
                .andExpect(jsonPath("$.data.content[0].roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.data.content[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("ADMIN이 아닌 사용자는 전체 사용자 목록을 조회할 수 없다")
    void getUsers_returnsForbidden_withoutAdminRole() throws Exception {
        mockMvc.perform(get(USERS_URL).with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(USERS_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("JWT의 관리자는 primary에서 ADMIN이 회수됐다면 캐시와 관계없이 403이다")
    void getUsers_returnsForbidden_whenCurrentPrimaryRoleIsNotAdmin() throws Exception {
        mockAdminJwt();
        given(roleAuthorityService.getRolesForAdmin(10L)).willReturn(List.of("USER"));

        mockMvc.perform(get(USERS_URL).header("Authorization", "Bearer current-token"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ROLE-002"));
    }

    @Test
    @DisplayName("JWT의 ADMIN이 primary에 남아 있으면 관리자 요청을 허용한다")
    void getUsers_allowsAdmin_whenCurrentPrimaryRoleIsAdmin() throws Exception {
        mockAdminJwt();
        given(roleAuthorityService.getRolesForAdmin(10L)).willReturn(List.of("ADMIN"));
        given(adminUserQueryService.getUsers(null, null, null, 0, 20))
            .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get(USERS_URL).header("Authorization", "Bearer current-token"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("primary 권한 확인에 실패하면 JWT 관리자 요청은 503이다")
    void getUsers_returnsUnavailable_whenPrimaryCannotVerifyRole() throws Exception {
        mockAdminJwt();
        given(roleAuthorityService.getRolesForAdmin(10L))
            .willThrow(new IllegalStateException("primary unavailable"));

        mockMvc.perform(get(USERS_URL).header("Authorization", "Bearer current-token"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("ROLE-005"));
    }

    private void mockAdminJwt() {
        Claims claims = mock(Claims.class);
        given(jwtProvider.getClaimsIfValid("current-token")).willReturn(claims);
        given(claims.get("jti", String.class)).willReturn("current-jti");
        given(claims.get("userId", Long.class)).willReturn(10L);
        given(claims.getSubject()).willReturn("admin@example.com");
        given(tokenBlacklistService.isBlacklisted("current-jti")).willReturn(false);
    }

    @Test
    @DisplayName("페이지 입력 범위를 벗어나면 400을 반환한다")
    void getUsers_returnsBadRequest_whenPageInputIsInvalid() throws Exception {
        mockMvc.perform(get(USERS_URL)
                        .param("page", "-1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    @DisplayName("ADMIN 사용자가 대상 사용자의 부서를 변경한다")
    void changeDepartment_returnsUpdatedUser() throws Exception {
        AdminUserResponse response = new AdminUserResponse(
                10L,
                "홍길동",
                "hong@example.com",
                "hong",
                5L,
                "영업팀",
                UserStatus.ACTIVE,
                List.of("USER"),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 1, 1, 10, 0)
        );
        given(userCommandService.changeDepartment(10L, new ChangeDepartmentRequest(5L))).willReturn(response);

        mockMvc.perform(patch(USERS_URL + "/{userId}/department", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"departmentId":5}
                            """)
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(10))
                .andExpect(jsonPath("$.data.departmentId").value(5))
                .andExpect(jsonPath("$.data.departmentName").value("영업팀"));
    }

    @Test
    @DisplayName("ADMIN이 아닌 사용자는 부서를 변경할 수 없다")
    void changeDepartment_returnsForbidden_withoutAdminRole() throws Exception {
        mockMvc.perform(patch(USERS_URL + "/{userId}/department", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"departmentId":5}
                            """)
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("departmentId가 없으면 400을 반환한다")
    void changeDepartment_returnsBadRequest_whenDepartmentIdIsMissing() throws Exception {
        mockMvc.perform(patch(USERS_URL + "/{userId}/department", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    @DisplayName("ADMIN 사용자가 대상 사용자의 역할을 회수한다")
    void revokeRole_returnsRemainingRoles() throws Exception {
        UserRoleResponse response = new UserRoleResponse(10L, "hong@example.com", "홍길동", List.of("USER"));
        given(userRoleCommandService.revokeRole(10L, "ADMIN")).willReturn(response);

        mockMvc.perform(delete(USERS_URL + "/{userId}/roles/{roleCode}", 10L, "ADMIN")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(10))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));
    }

    @Test
    @DisplayName("ADMIN이 아닌 사용자는 역할을 회수할 수 없다")
    void revokeRole_returnsForbidden_withoutAdminRole() throws Exception {
        mockMvc.perform(delete(USERS_URL + "/{userId}/roles/{roleCode}", 10L, "ADMIN")
                        .with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("부여되지 않은 역할을 회수하려 하면 404를 반환한다")
    void revokeRole_returnsNotFound_whenRoleNotAssigned() throws Exception {
        given(userRoleCommandService.revokeRole(10L, "ADMIN"))
                .willThrow(new DocGridException(ErrorCode.ROLE_NOT_ASSIGNED));

        mockMvc.perform(delete(USERS_URL + "/{userId}/roles/{roleCode}", 10L, "ADMIN")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE-004"));
    }
}
