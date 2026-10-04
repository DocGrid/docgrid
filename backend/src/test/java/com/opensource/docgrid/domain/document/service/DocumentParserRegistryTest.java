package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * 문서 형식별 Parser 선택, 미등록 형식과 중복 등록의 실패 계약을 검증한다.
 */
@DisplayName("DocumentParserRegistry 테스트")
class DocumentParserRegistryTest {

    private final TextDocumentParser textDocumentParser = new TextDocumentParser();

    @Test
    @DisplayName("TXT와 Markdown은 각 형식에 등록된 Parser로 변환한다")
    void parse_selectsRegisteredParser() {
        DocumentParserRegistry registry = new DocumentParserRegistry(
            List.of(textDocumentParser, new MarkdownDocumentParser(textDocumentParser))
        );
        byte[] content = "# 제목\n본문\n# 다음\n본문 둘".getBytes(StandardCharsets.UTF_8);

        assertThat(registry.parse(DocumentType.TXT, content).segments()).hasSize(1);
        assertThat(registry.parse(DocumentType.MD, content).segments()).hasSize(2);
    }

    @Test
    @DisplayName("등록되지 않은 형식이면 지원하지 않는 문서 오류가 발생한다")
    void parse_throwsWhenParserIsMissing() {
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(textDocumentParser));

        assertThatThrownBy(() -> registry.parse(DocumentType.PDF, new byte[] {1}))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE));
    }

    @Test
    @DisplayName("같은 형식 Parser가 중복 등록되면 생성에 실패한다")
    void constructor_throwsWhenParserRegistrationIsDuplicated() {
        assertThatThrownBy(() -> new DocumentParserRegistry(List.of(textDocumentParser, textDocumentParser)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("중복");
    }
}
