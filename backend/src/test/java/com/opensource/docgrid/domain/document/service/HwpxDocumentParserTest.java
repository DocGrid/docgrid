package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * HWPX 본문·표 추출과 빈·손상·외부 XML 개체 차단을 재배포 가능한 샘플로 검증한다.
 */
@DisplayName("HwpxDocumentParser 테스트")
class HwpxDocumentParserTest {

    private final HwpxDocumentParser parser = new HwpxDocumentParser();

    @Test
    @DisplayName("HWPX 표 셀 텍스트를 검색 가능한 Segment에 포함한다")
    void parseDocument_extractsTableCells() throws IOException {
        ParsedDocument document = parser.parseDocument(fixture("table.hwpx"));

        assertThat(parser.supportedTypes()).containsExactly(DocumentType.HWPX);
        assertThat(document.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.text()).contains("이름", "국어", "개똥이", "89");
            assertThat(segment.pageNo()).isNull();
        });
    }

    @Test
    @DisplayName("빈 HWPX는 인덱싱할 텍스트가 없다는 오류를 반환한다")
    void parseDocument_rejectsBlankDocument() throws IOException {
        assertError(fixture("blank.hwpx"), ErrorCode.DOCUMENT_CONTENT_EMPTY);
    }

    @Test
    @DisplayName("손상된 HWPX는 파싱 실패로 처리한다")
    void parseDocument_rejectsCorruptDocument() {
        assertError(new byte[] {1, 2, 3}, ErrorCode.DOCUMENT_PARSING_FAILED);
    }

    @Test
    @DisplayName("HWPX XML의 DTD와 외부 개체는 라이브러리 읽기 전에 거부한다")
    void parseDocument_rejectsXmlEntity() throws IOException {
        assertError(withDtd(fixture("table.hwpx")), ErrorCode.DOCUMENT_PARSING_FAILED);
    }

    @Test
    @DisplayName("암호화 항목이 표시된 HWPX manifest는 인덱싱하지 않는다")
    void parseDocument_rejectsEncryptedManifest() throws IOException {
        assertError(withEncryptionManifest(fixture("table.hwpx")), ErrorCode.DOCUMENT_PARSING_FAILED);
    }

    private byte[] fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/hancom/" + name)) {
            return input.readAllBytes();
        }
    }

    private byte[] withDtd(byte[] original) throws IOException {
        return rewriteZip(original, "Contents/section0.xml",
            bytes -> ("<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>")
                .concat(new String(bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
    }

    private byte[] withEncryptionManifest(byte[] original) throws IOException {
        return rewriteZip(original, "META-INF/manifest.xml", bytes ->
            new String(bytes, StandardCharsets.UTF_8).replace(
                "/>", "><odf:file-entry odf:full-path=\"Contents/section0.xml\""
                    + " odf:media-type=\"application/xml\"><odf:encryption-data/>"
                    + "</odf:file-entry></odf:manifest>"
            ).getBytes(StandardCharsets.UTF_8));
    }

    private byte[] rewriteZip(byte[] original, String target,
                              java.util.function.Function<byte[], byte[]> transform) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new java.io.ByteArrayInputStream(original));
             ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                ZipEntry copy = new ZipEntry(entry.getName());
                zip.putNextEntry(copy);
                byte[] bytes = input.readAllBytes();
                if (entry.getName().equals(target)) {
                    bytes = transform.apply(bytes);
                }
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private void assertError(byte[] content, ErrorCode errorCode) {
        assertThatThrownBy(() -> parser.parseDocument(content))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", errorCode);
    }
}
