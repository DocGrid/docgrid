package com.opensource.docgrid.domain.document.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.request.DocumentVersionUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionUploadResponse;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.service.DocumentVersionUploadFacade;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 최초 문서와 기존 문서의 새 버전 파일을 접수하는 Multipart HTTP API를 제공한다.
 *
 * <p>요청 Bean Validation과 HTTP 응답 변환만 담당하고, 파일 검증·저장소 작업·DB 트랜잭션 및
 * 보상 삭제는 각 업로드 Facade에 위임한다. 최초·새 버전 업로드의 선택적 멱등 키를 전달하며,
 * 파싱과 임베딩은 이 요청에서 실행하지 않는다.
 */
@Tag(name = "Document", description = "문서 관련 API")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentUploadController {

    private final DocumentUploadFacade documentUploadFacade;
    private final DocumentVersionUploadFacade documentVersionUploadFacade;

    /**
     * 새 문서의 원본 파일과 Metadata를 접수하고 비동기 인덱싱 Job 생성 결과를 반환한다.
     */
    @Operation(
        summary = "문서 업로드 접수",
        description = "TXT, Markdown, PDF, DOCX, HWP 5.x 또는 HWPX 원본 파일을 저장하고 비동기 인덱싱 작업을 생성합니다. "
            + "파싱과 임베딩은 수행하지 않습니다. 확장자와 Content-Type이 함께 맞아야 하며, "
            + "구형 DOC 형식은 지원하지 않습니다. Idempotency-Key를 보내면 같은 입력의 재시도는 최초 결과를 반환합니다."
    )
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> upload(
        @Valid @ModelAttribute DocumentUploadRequest request,
        @Parameter(hidden = true) @CurrentUser Long userId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseUtils.created(documentUploadFacade.upload(userId, request, idempotencyKey));
    }

    /**
     * 기존 문서에 새 원본 파일 버전을 접수하고 해당 버전의 인덱싱 Job 생성 결과를 반환한다.
     */
    @Operation(
        summary = "문서 새 버전 업로드 접수",
        description = "기존 문서에 새 파일 버전을 등록하고 비동기 인덱싱 작업을 생성합니다. "
            + "문서 소유자 또는 계산된 WRITE 권한이 필요합니다. "
            + "Idempotency-Key를 보내면 같은 문서·입력의 재시도는 최초 결과를 반환합니다."
    )
    @PostMapping(value = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentVersionUploadResponse>> uploadVersion(
        @PathVariable Long documentId,
        @Valid @ModelAttribute DocumentVersionUploadRequest request,
        @Parameter(hidden = true) @CurrentUser Long userId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseUtils.created(documentVersionUploadFacade.upload(userId, documentId, request, idempotencyKey));
    }
}
