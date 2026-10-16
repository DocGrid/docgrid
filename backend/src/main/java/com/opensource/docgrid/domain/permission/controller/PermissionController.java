package com.opensource.docgrid.domain.permission.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestHeader;
import com.opensource.docgrid.domain.permission.service.DocumentPermissionRevocationFacade;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.permission.dto.request.GrantPermissionRequest;
import com.opensource.docgrid.domain.permission.dto.response.CollectionPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionResponse;
import com.opensource.docgrid.domain.permission.dto.response.DocumentPermissionSummaryResponse;
import com.opensource.docgrid.domain.permission.dto.response.PermissionTargetUserResponse;
import com.opensource.docgrid.domain.permission.service.command.CollectionPermissionCommandService;
import com.opensource.docgrid.domain.permission.service.command.DocumentPermissionCommandService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.permission.service.query.PermissionTargetUserQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 문서·컬렉션의 현재 권한 조회, 직접 권한 관리와 권한 부여 대상 사용자 검색을 HTTP API로 제공한다.
 *
 * <p>HTTP 입력 검증과 응답 상태만 담당하며 ADMIN 가능 여부, 권한 상속·캐시와 Sync Event 기록은
 * Command·Query Service에 위임한다.
 */
@Tag(name = "Permission", description = "권한 관련 API")
@Validated
@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final CollectionPermissionCommandService collectionPermissionCommandService;
    private final DocumentPermissionCommandService documentPermissionCommandService;
    private final PermissionQueryService permissionQueryService;
    private final PermissionTargetUserQueryService permissionTargetUserQueryService;
    private final DocumentPermissionRevocationFacade documentPermissionRevocationFacade;

    @Operation(
            summary = "내 문서 권한 확인",
            description = "현재 로그인한 사용자가 특정 문서에 대해 읽기·쓰기·관리 권한을 가지고 있는지, " +
                    "그리고 그 권한이 어떤 경로(OWNER/PUBLIC/USER_CACHE/ROLE/DEPARTMENT)로 부여됐는지 반환합니다. " +
                    "sources는 실제 권한이 부여된 경로만 포함됩니다. 권한이 전혀 없으면 sources는 빈 배열입니다."
    )
    @GetMapping("/documents/{documentId}/me")
    public ResponseEntity<ApiResponse<DocumentPermissionSummaryResponse>> getMyDocumentPermission(
            @PathVariable Long documentId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ResponseUtils.ok(permissionQueryService.checkDocumentPermission(userId, documentId));
    }

    @Operation(
            summary = "문서 직접 권한 목록 조회",
            description = "문서 ADMIN 권한 보유자가 해당 문서에 직접 부여된 USER/ROLE/DEPARTMENT 권한 전체를 조회합니다. " +
                    "계산되거나 컬렉션에서 상속된 권한은 포함하지 않으며 만료된 직접 권한은 포함합니다."
    )
    @GetMapping("/documents/{documentId}")
    public ResponseEntity<ApiResponse<List<DocumentPermissionResponse>>> getDocumentPermissions(
            @PathVariable Long documentId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ResponseUtils.ok(permissionQueryService.getDocumentPermissions(userId, documentId));
    }

    @Operation(
            summary = "컬렉션 직접 권한 목록 조회",
            description = "컬렉션 ADMIN 권한 보유자가 해당 컬렉션에 직접 부여된 USER/ROLE/DEPARTMENT 권한 전체를 조회합니다. " +
                    "계산된 권한은 포함하지 않으며 만료된 직접 권한은 포함합니다."
    )
    @GetMapping("/collections/{collectionId}")
    public ResponseEntity<ApiResponse<List<CollectionPermissionResponse>>> getCollectionPermissions(
            @PathVariable Long collectionId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ResponseUtils.ok(permissionQueryService.getCollectionPermissions(userId, collectionId));
    }

    @Operation(
            summary = "문서 권한 부여 대상 사용자 검색",
            description = "문서 ADMIN 권한 보유자가 이름 또는 이메일로 활성 사용자를 검색합니다. " +
                    "동명이인 구분을 위해 이름·이메일·부서와 내부 사용자 ID를 반환합니다."
    )
    @GetMapping("/documents/{documentId}/users")
    public ResponseEntity<ApiResponse<List<PermissionTargetUserResponse>>> searchDocumentPermissionUsers(
            @PathVariable Long documentId,
            @RequestParam @NotBlank @Size(max = 100) String keyword,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ResponseUtils.ok(permissionTargetUserQueryService.searchForDocument(userId, documentId, keyword));
    }

    @Operation(
            summary = "컬렉션 권한 부여 대상 사용자 검색",
            description = "컬렉션 ADMIN 권한 보유자가 이름 또는 이메일로 활성 사용자를 검색합니다. " +
                    "동명이인 구분을 위해 이름·이메일·부서와 내부 사용자 ID를 반환합니다."
    )
    @GetMapping("/collections/{collectionId}/users")
    public ResponseEntity<ApiResponse<List<PermissionTargetUserResponse>>> searchCollectionPermissionUsers(
            @PathVariable Long collectionId,
            @RequestParam @NotBlank @Size(max = 100) String keyword,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ResponseUtils.ok(permissionTargetUserQueryService.searchForCollection(userId, collectionId, keyword));
    }

    @Operation(
            summary = "컬렉션 권한 부여",
            description = "컬렉션에 USER/ROLE/DEPARTMENT 단위로 권한을 부여합니다. 컬렉션 ADMIN 권한 보유자(소유자 포함)만 가능합니다. " +
                    "targetType에 맞는 ID 필드(userId/roleId/departmentId) 하나만 입력해야 합니다. " +
                    "USER 대상인 경우 컬렉션 내 문서에 대한 접근 캐시가 즉시 갱신됩니다."
    )
    @PostMapping("/collections/{collectionId}")
    public ResponseEntity<ApiResponse<CollectionPermissionResponse>> grantCollectionPermission(
            @PathVariable Long collectionId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestBody @Valid GrantPermissionRequest request) {
        return ResponseUtils.created(
                collectionPermissionCommandService.grantPermission(collectionId, userId, request));
    }

    @Operation(
            summary = "컬렉션 권한 회수",
            description = "부여된 컬렉션 권한을 회수합니다. 컬렉션 ADMIN 권한 보유자(소유자 포함)만 가능합니다. " +
                    "USER 대상 권한이었다면 접근 캐시도 즉시 무효화됩니다."
    )
    @DeleteMapping("/collections/{collectionId}/{permissionId}")
    public ResponseEntity<ApiResponse<Void>> revokeCollectionPermission(
            @PathVariable Long collectionId,
            @PathVariable Long permissionId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        collectionPermissionCommandService.revokePermission(collectionId, permissionId, userId);
        return ResponseUtils.noContent();
    }

    @Operation(
            summary = "문서 예외 권한 부여",
            description = "특정 문서 하나에만 적용되는 예외 권한을 부여합니다. 문서 ADMIN 권한 보유자(소유자 포함)만 가능합니다. " +
                    "기본 권한은 collection_permissions로 관리하고, 이 API는 예외 케이스에만 최소한으로 사용하세요. " +
                    "USER 대상인 경우 해당 문서의 접근 캐시가 즉시 갱신됩니다."
    )
    @PostMapping("/documents/{documentId}")
    public ResponseEntity<ApiResponse<DocumentPermissionResponse>> grantDocumentPermission(
            @PathVariable Long documentId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestBody @Valid GrantPermissionRequest request) {
        return ResponseUtils.created(
                documentPermissionCommandService.grantPermission(documentId, userId, request));
    }

    @Operation(
            summary = "문서 예외 권한 회수",
            description = "부여된 문서 예외 권한을 회수합니다. 문서 ADMIN 권한 보유자(소유자 포함)만 가능합니다. " +
                    "USER 대상 권한이었다면 해당 문서의 접근 캐시도 즉시 무효화됩니다."
    )
    @DeleteMapping("/documents/{documentId}/{permissionId}")
    public ResponseEntity<ApiResponse<Void>> revokeDocumentPermission(
            @PathVariable Long documentId,
            @PathVariable Long permissionId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID requestId) {
        documentPermissionRevocationFacade.revoke(documentId, permissionId, userId, requestId);
        return ResponseUtils.noContent();
    }
}
