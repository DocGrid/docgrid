package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * DOCX Heading·본문·Table의 문서 순서와 Section Title, 빈·손상 문서 오류를 검증한다.
 */
@DisplayName("DocxDocumentParser 테스트")
class DocxDocumentParserTest {

    private final DocxDocumentParser parser = new DocxDocumentParser();

    @Test
    @DisplayName("Heading 이전 본문과 Section별 본문·Table 순서를 보존한다")
    void parseDocument_extractsBodyElementsBySection() throws IOException {
        byte[] docx = structuredDocx();

        ParsedDocument result = parser.parseDocument(docx);

        assertThat(parser.supportedTypes()).containsExactly(DocumentType.DOCX);
        assertThat(result.segments()).hasSize(3);
        assertThat(result.segments().get(0).sectionTitle()).isNull();
        assertThat(result.segments().get(0).text()).isEqualTo("introduction");
        assertThat(result.segments().get(1).sectionTitle()).isEqualTo("Section A");
        assertThat(result.segments().get(1).text())
            .isEqualTo("Section A\nparagraph A\nA1\tA2\nB1\tB2");
        assertThat(result.segments().get(2).sectionTitle()).isEqualTo("Section B");
        assertThat(result.segments().get(2).text()).isEqualTo("Section B\nparagraph B");
    }

    @Test
    @DisplayName("Heading 레벨로 상위→하위 경로를 만들고 같거나 높은 레벨이 오면 이전 경로를 닫는다")
    void parseDocument_buildsHeadingPathByLevel() throws IOException {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument()) {
            heading(document, "Heading1", "3. 환불 정책");
            body(document, "정책 개요");
            heading(document, "Heading2", "3.1 일반 환불");
            body(document, "일반 본문");
            heading(document, "Heading3", "3.1.1 예외");
            body(document, "예외 본문");
            heading(document, "Heading2", "3.2 개봉 후 환불");
            body(document, "개봉 본문");
            heading(document, "Heading1", "4. 배송");
            body(document, "배송 본문");
            heading(document, "Heading3", "4.0.1 레벨 건너뜀");
            body(document, "건너뜀 본문");
            docx = save(document);
        }

        ParsedDocument result = parser.parseDocument(docx);

        assertThat(result.segments()).extracting(ParsedDocumentSegment::sectionTitle).containsExactly(
            "3. 환불 정책", "3.1 일반 환불", "3.1.1 예외", "3.2 개봉 후 환불", "4. 배송", "4.0.1 레벨 건너뜀");
        assertThat(result.segments()).extracting(ParsedDocumentSegment::metadataJson).containsExactly(
            SectionPath.toMetadataJson(List.of("3. 환불 정책"), 1),
            SectionPath.toMetadataJson(List.of("3. 환불 정책", "3.1 일반 환불"), 2),
            SectionPath.toMetadataJson(List.of("3. 환불 정책", "3.1 일반 환불", "3.1.1 예외"), 3),
            SectionPath.toMetadataJson(List.of("3. 환불 정책", "3.2 개봉 후 환불"), 2),
            SectionPath.toMetadataJson(List.of("4. 배송"), 1),
            SectionPath.toMetadataJson(List.of("4. 배송", "4.0.1 레벨 건너뜀"), 3));
    }

    @Test
    @DisplayName("문서 Title은 모든 Heading의 상위 경로가 되고 Heading 이전 본문은 경로가 없다")
    void parseDocument_treatsTitleAsRootOfHeadingPath() throws IOException {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument()) {
            body(document, "머리말");
            heading(document, "Title", "쇼핑몰 이용약관");
            heading(document, "Heading1", "1. 총칙");
            body(document, "총칙 본문");
            docx = save(document);
        }

        ParsedDocument result = parser.parseDocument(docx);

        assertThat(result.segments()).hasSize(3);
        assertThat(result.segments().get(0).metadataJson()).isNull();
        assertThat(result.segments().get(1).metadataJson())
            .isEqualTo(SectionPath.toMetadataJson(List.of("쇼핑몰 이용약관"), 0));
        assertThat(result.segments().get(2).metadataJson())
            .isEqualTo(SectionPath.toMetadataJson(List.of("쇼핑몰 이용약관", "1. 총칙"), 1));
    }

    @Test
    @DisplayName("검색 가능한 본문이 없는 DOCX면 빈 문서 오류가 발생한다")
    void parseDocument_throwsWhenDocumentIsEmpty() throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            assertError(save(document), ErrorCode.DOCUMENT_CONTENT_EMPTY);
        }
    }

    @Test
    @DisplayName("손상된 DOCX면 제한된 파싱 실패 오류가 발생한다")
    void parseDocument_throwsWhenDocumentIsCorrupted() {
        assertError(new byte[] {1, 2, 3}, ErrorCode.DOCUMENT_PARSING_FAILED);
    }

    private byte[] structuredDocx() throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("introduction");
            heading(document, "Section A");
            document.createParagraph().createRun().setText("paragraph A");

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("A1");
            table.getRow(0).getCell(1).setText("A2");
            table.getRow(1).getCell(0).setText("B1");
            table.getRow(1).getCell(1).setText("B2");

            heading(document, "Section B");
            document.createParagraph().createRun().setText("paragraph B");
            return save(document);
        }
    }

    private void heading(XWPFDocument document, String text) {
        heading(document, "Heading1", text);
    }

    private void heading(XWPFDocument document, String style, String text) {
        XWPFParagraph heading = document.createParagraph();
        heading.setStyle(style);
        heading.createRun().setText(text);
    }

    private void body(XWPFDocument document, String text) {
        document.createParagraph().createRun().setText(text);
    }

    private byte[] save(XWPFDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(output);
        return output.toByteArray();
    }

    private void assertError(byte[] content, ErrorCode errorCode) {
        assertThatThrownBy(() -> parser.parseDocument(content))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
