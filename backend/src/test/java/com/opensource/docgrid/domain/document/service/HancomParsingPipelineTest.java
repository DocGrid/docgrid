package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.config.DocumentChunkingProperties;
import com.opensource.docgrid.domain.document.enums.DocumentType;

/**
 * HWP·HWPX의 파서 등록부터 검색용 Chunk 계산까지 기존 인덱싱 계산 경계를 검증한다.
 */
@DisplayName("한글 문서 파싱·Chunk 연결 테스트")
class HancomParsingPipelineTest {

    private final DocumentParserRegistry registry = new DocumentParserRegistry(
        List.of(new HwpDocumentParser(), new HwpxDocumentParser())
    );
    private final FixedSizeChunker chunker = new FixedSizeChunker(new DocumentChunkingProperties());

    @Test
    @DisplayName("HWP 표 내용이 검색용 Chunk에 남는다")
    void hwpTableTextRemainsInChunks() throws IOException {
        assertTableTextInChunks(DocumentType.HWP, "table.hwp", "ABC");
    }

    @Test
    @DisplayName("HWPX 표 내용이 검색용 Chunk에 남는다")
    void hwpxTableTextRemainsInChunks() throws IOException {
        assertTableTextInChunks(DocumentType.HWPX, "table.hwpx", "개똥이");
    }

    private void assertTableTextInChunks(DocumentType type, String name, String text) throws IOException {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/hancom/" + name)) {
            bytes = input.readAllBytes();
        }

        ParsedDocument parsed = registry.parse(type, bytes);
        List<DocumentChunkDraft> chunks = chunker.chunk(parsed);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).extracting(DocumentChunkDraft::chunkText)
            .anySatisfy(chunkText -> assertThat(chunkText).contains(text));
    }
}
