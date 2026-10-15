package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 라이선스가 확인된 HWP 샘플로 본문·표 추출과 빈·손상 문서의 오류 계약을 검증한다.
 */
@DisplayName("HwpDocumentParser 테스트")
class HwpDocumentParserTest {

    private final HwpDocumentParser parser = new HwpDocumentParser();

    @Test
    @DisplayName("HWP 표 셀 텍스트를 검색 가능한 Segment에 포함한다")
    void parseDocument_extractsTableCells() throws IOException {
        ParsedDocument document = parser.parseDocument(fixture("table.hwp"));

        assertThat(parser.supportedTypes()).containsExactly(DocumentType.HWP);
        assertThat(document.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.text()).contains("ABC", "123");
            assertThat(segment.pageNo()).isNull();
        });
    }

    @Test
    @DisplayName("빈 HWP는 인덱싱할 텍스트가 없다는 오류를 반환한다")
    void parseDocument_rejectsBlankDocument() throws IOException {
        assertError(fixture("blank.hwp"), ErrorCode.DOCUMENT_CONTENT_EMPTY);
    }

    @Test
    @DisplayName("손상된 HWP는 파싱 실패로 처리한다")
    void parseDocument_rejectsCorruptDocument() {
        assertError(new byte[] {1, 2, 3}, ErrorCode.DOCUMENT_PARSING_FAILED);
    }

    @Test
    @DisplayName("암호 비트가 설정된 HWP는 정상 문서로 인덱싱하지 않는다")
    void parseDocument_rejectsPasswordFlag() throws IOException {
        byte[] protectedFile;
        try (POIFSFileSystem ole = new POIFSFileSystem(new ByteArrayInputStream(fixture("table.hwp")));
             var header = ole.createDocumentInputStream("FileHeader");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = header.readAllBytes();
            bytes[36] |= 0x02;
            ole.getRoot().createOrUpdateDocument("FileHeader", new ByteArrayInputStream(bytes));
            ole.writeFilesystem(output);
            protectedFile = output.toByteArray();
        }

        assertError(protectedFile, ErrorCode.DOCUMENT_PARSING_FAILED);
    }

    private byte[] fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/hancom/" + name)) {
            return input.readAllBytes();
        }
    }

    private void assertError(byte[] content, ErrorCode errorCode) {
        assertThatThrownBy(() -> parser.parseDocument(content))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", errorCode);
    }
}
