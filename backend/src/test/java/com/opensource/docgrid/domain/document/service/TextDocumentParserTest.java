package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import com.opensource.docgrid.domain.document.enums.DocumentType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * TXT와 Markdown 원본의 엄격한 UTF-8 Decode 및 Canonical Text 정규화 계약을 검증한다.
 *
 * <p>BOM과 줄바꿈만 정규화하고 Markdown 문법, 일반 공백과 Unicode 문자를 보존하는지 확인한다.
 */
@DisplayName("TextDocumentParser 테스트")
class TextDocumentParserTest {

    private final TextDocumentParser parser = new TextDocumentParser();

    @Test
    @DisplayName("정상 UTF-8 TXT 본문을 그대로 Decode한다")
    void parse_decodesUtf8Text() {
        String source = "DocGrid 한글 😀 e\u0301";

        assertThat(parser.parse(source.getBytes(StandardCharsets.UTF_8))).isEqualTo(source);
    }

    @Test
    @DisplayName("Markdown 문법과 일반 공백을 보존한다")
    void parse_preservesMarkdownAndWhitespace() {
        String markdown = "  # 제목\n\n[링크](https://example.com)\n```java\nint n = 1;\n```\n  ";

        assertThat(parser.parse(markdown.getBytes(StandardCharsets.UTF_8))).isEqualTo(markdown);
    }

    @Test
    @DisplayName("선두 BOM 한 개를 제거하고 CRLF와 CR을 LF로 통일한다")
    void parse_normalizesBomAndLineEndings() {
        String source = "\uFEFF첫 줄\r\n둘째 줄\r셋째 줄\uFEFF";

        assertThat(parser.parse(source.getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("첫 줄\n둘째 줄\n셋째 줄\uFEFF");
    }

    @Test
    @DisplayName("TXT를 단일 Segment Parsed Document로 변환한다")
    void parseDocument_wrapsCanonicalTextAsSingleSegment() {
        ParsedDocument result = parser.parseDocument("본문\r\n둘째 줄".getBytes(StandardCharsets.UTF_8));

        assertThat(parser.supportedTypes()).containsExactly(DocumentType.TXT);
        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.text()).isEqualTo("본문\n둘째 줄");
            assertThat(segment.pageNo()).isNull();
            assertThat(segment.sectionTitle()).isNull();
        });
    }

    @Test
    @DisplayName("빈 Byte와 공백뿐인 문서는 거부한다")
    void parse_rejectsEmptyContent() {
        assertParsingError(new byte[0], ErrorCode.DOCUMENT_CONTENT_EMPTY);
        assertParsingError(" \n\t".getBytes(StandardCharsets.UTF_8), ErrorCode.DOCUMENT_CONTENT_EMPTY);
    }

    @Test
    @DisplayName("잘못된 UTF-8 Byte를 대체 문자 없이 거부한다")
    void parse_rejectsMalformedUtf8() {
        assertParsingError(new byte[] {(byte) 0xC3, (byte) 0x28}, ErrorCode.DOCUMENT_TEXT_DECODING_FAILED);
    }

    private void assertParsingError(byte[] content, ErrorCode errorCode) {
        assertThatThrownBy(() -> parser.parse(content))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
