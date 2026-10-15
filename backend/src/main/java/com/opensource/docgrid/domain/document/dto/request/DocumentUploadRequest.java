package com.opensource.docgrid.domain.document.dto.request;

import org.springframework.web.multipart.MultipartFile;

import com.opensource.docgrid.domain.document.enums.VisibilityType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 새 문서 업로드 API가 받는 파일과 문서 메타데이터 입력이다.
 *
 * <p>파일 내용·확장자·크기 검증은 저장 전 FileValidationService가 담당하고, 이 요청은 HTTP 필수값과
 * 제목 길이 같은 형식 검증만 선언한다.
 */
public record DocumentUploadRequest(
    @NotNull
    @Schema(description = "업로드할 TXT, Markdown, PDF, DOCX, HWP 5.x 또는 HWPX 파일", type = "string", format = "binary")
    MultipartFile file,

    @NotBlank
    @Size(max = 500)
    @Schema(description = "문서 제목")
    String title,

    @Schema(description = "문서 설명")
    String description,

    @NotNull
    @Schema(description = "문서 공개 범위")
    VisibilityType visibility
) {
}
