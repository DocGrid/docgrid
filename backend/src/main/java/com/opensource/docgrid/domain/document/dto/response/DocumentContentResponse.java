package com.opensource.docgrid.domain.document.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 현재 문서 버전의 Chunk 중복을 제거해 복원한 정규화 Text 전체를 반환한다.
 * 원본 PDF·DOCX·HWP·HWPX의 Binary, Layout, Image와 Font 정보는 이 응답에 포함하지 않는다.
 */
@Schema(description = "문서에서 추출한 전체 텍스트")
public record DocumentContentResponse(
    @Schema(description = "문서 ID") Long documentId,
    @Schema(description = "본문을 복원한 문서 버전 ID") Long documentVersionId,
    @Schema(description = "본문을 복원한 버전 번호") int versionNo,
    @Schema(description = "중복을 제거하고 원래 순서로 복원한 전체 텍스트") String content,
    @Schema(description = "본문 복원에 사용한 Chunk 수") int chunkCount
) {
}
