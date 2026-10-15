package com.opensource.docgrid.domain.document.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opensource.docgrid.domain.auth.annotation.CurrentUser;
import com.opensource.docgrid.domain.document.dto.response.DocumentContentResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentDetailResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentSummaryResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentVersionHistoryResponse;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.service.DocumentFileDownload;
import com.opensource.docgrid.domain.document.service.DocumentFileService;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.global.common.response.ApiResponse;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.common.response.ResponseUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 사용자의 문서 목록·상세·버전·추출 본문·원본 파일·인덱싱 상태 조회 API를 제공한다.
 *
 * <p>HTTP 파라미터와 파일 응답 Header 조립만 담당하며, 문서 접근 권한과 조회 규칙은
 * {@link DocumentQueryService} 및 {@link DocumentFileService}에 위임한다. Entity와 저장소 내부 위치는
 * 외부에 노출하지 않는다.
 */
@Tag(name = "Document", description = "문서 관련 API")
@Validated
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentQueryController {

    private final DocumentQueryService documentQueryService;
    private final DocumentFileService documentFileService;

    /**
     * 사용자가 읽을 수 있는 문서를 선택적 상태 조건과 페이지 단위로 조회한다.
     */
    @Operation(
        summary = "내 문서 목록 조회",
        description = "로그인한 사용자가 읽을 수 있는 문서를 최신 등록순으로 조회합니다. "
            + "소유한 문서, PUBLIC 문서, 문서·컬렉션 권한을 부여받은 문서가 모두 포함됩니다. "
            + "status를 지정하면 해당 상태만 조회하며, 미지정 시 삭제된 문서를 제외한 전체를 반환합니다. "
            + "size는 1~100까지 지정할 수 있습니다."
    )
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<DocumentSummaryResponse>>> getMyDocuments(
        @Parameter(hidden = true) @CurrentUser Long userId,
        @RequestParam(required = false) DocumentStatus status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseUtils.ok(documentQueryService.getMyDocuments(userId, status, page, size));
    }

    /**
     * 읽기 권한이 있는 문서의 Metadata와 현재 버전 요약을 조회한다.
     */
    @Operation(
        summary = "문서 상세 조회",
        description = "문서 Metadata, 소유자와 현재 버전 정보를 조회합니다. 추출 본문과 원본 파일은 포함하지 않습니다. "
            + "문서 읽기 권한이 필요하며 삭제된 문서는 조회할 수 없습니다."
    )
    @GetMapping("/{documentId}")
    public ResponseEntity<ApiResponse<DocumentDetailResponse>> getDocumentDetail(
        @PathVariable Long documentId,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        return ResponseUtils.ok(documentQueryService.getDocumentDetail(userId, documentId));
    }

    /**
     * 읽기 권한이 있는 문서의 전체 버전과 각 인덱싱 상태를 최신 순으로 조회한다.
     */
    @Operation(
        summary = "문서 버전 전체 이력 조회",
        description = "읽기 가능한 문서의 모든 버전을 최신 번호순으로 조회합니다. 현재 검색 버전 여부, "
            + "파일 Snapshot, 생성자, 버전 상태와 각 버전의 최신 인덱싱 Job 상태를 함께 반환합니다."
    )
    @GetMapping("/{documentId}/versions")
    public ResponseEntity<ApiResponse<List<DocumentVersionHistoryResponse>>> getDocumentVersions(
        @PathVariable Long documentId,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        return ResponseUtils.ok(documentQueryService.getDocumentVersions(userId, documentId));
    }

    /**
     * 현재 검색 버전의 Chunk를 문서 순서로 복원한 추출 본문을 조회한다.
     */
    @Operation(
        summary = "문서 추출 본문 조회",
        description = "현재 버전의 Chunk 중복을 제거하고 페이지·섹션 순서대로 복원한 정규화 Text 전체를 반환합니다. "
            + "원본 PDF·DOCX·HWP·HWPX의 Layout, Image와 Font는 포함하지 않으며 문서 읽기 권한이 필요합니다."
    )
    @GetMapping("/{documentId}/content")
    public ResponseEntity<ApiResponse<DocumentContentResponse>> getDocumentContent(
        @PathVariable Long documentId,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        return ResponseUtils.ok(documentQueryService.getDocumentContent(userId, documentId));
    }

    /**
     * 현재 문서 버전의 원본 파일을 브라우저 표시 또는 첨부 다운로드 응답으로 반환한다.
     */
    @Operation(
        summary = "문서 원본 파일 조회",
        description = "현재 버전의 원본 PDF·DOCX·HWP·HWPX·TXT·MD 파일을 반환합니다. disposition은 inline 또는 attachment이며 "
            + "기본값 inline은 브라우저 표시, attachment는 다운로드에 사용합니다. 문서 읽기 권한이 필요합니다."
    )
    @GetMapping("/{documentId}/file")
    public ResponseEntity<byte[]> getDocumentFile(
        @PathVariable Long documentId,
        @RequestParam(defaultValue = "inline")
        @Pattern(regexp = "inline|attachment", message = "disposition은 inline 또는 attachment여야 합니다.")
        String disposition,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        // 1. Service가 권한을 검증하고 현재 버전의 원본 Byte와 안전한 파일 Metadata를 반환한다.
        DocumentFileDownload download = documentFileService.getDocumentFile(userId, documentId);

        // 2. 저장소 내부 위치는 숨기고 브라우저 표시·다운로드에 필요한 표준 Header만 설정한다.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(resolveMediaType(download.contentType()));
        headers.setContentLength(download.fileSize());
        headers.setContentDisposition(ContentDisposition.builder(disposition)
            .filename(download.originalFilename(), StandardCharsets.UTF_8)
            .build());
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(download.content());
    }

    /**
     * 현재 검색 버전과 별도로 처리 중인 버전·Job 상태를 조회한다.
     */
    @Operation(
        summary = "문서 인덱싱 상태 조회",
        description = "현재 검색 가능한 INDEXED 버전과 처리 중인 버전 및 임베딩 작업 상태를 함께 조회합니다. "
            + "최초 버전이 아직 처리 중이면 currentVersion은 null입니다. 문서 읽기 권한이 필요합니다."
    )
    @GetMapping("/{documentId}/status")
    public ResponseEntity<ApiResponse<DocumentStatusResponse>> getDocumentStatus(
        @PathVariable Long documentId,
        @Parameter(hidden = true) @CurrentUser Long userId
    ) {
        return ResponseUtils.ok(documentQueryService.getDocumentStatus(userId, documentId));
    }

    /**
     * 저장된 Content-Type을 HTTP MediaType으로 변환하고 비어 있거나 잘못된 값은 안전한 Binary 형식으로 대체한다.
     */
    private MediaType resolveMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
