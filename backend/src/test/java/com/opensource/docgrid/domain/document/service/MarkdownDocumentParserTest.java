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
 * Markdown ATX Heading 기준 Section 분리, 계층 경로 Metadata와 원문 복원 계약을 검증한다.
 */
@DisplayName("MarkdownDocumentParser 테스트")
class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser(new TextDocumentParser());

    @Test
    @DisplayName("Heading마다 Section을 나누고 상위→하위 경로를 Metadata로 남긴다")
    void parseDocument_splitsSectionsWithHeadingPath() {
        ParsedDocument result = parse("서문\n\n# 환불 정책\n정책 개요\n## 개봉 후 환불\n배송비를 공제한다.\n# 배송\n배송 안내");

        assertThat(parser.supportedTypes()).containsExactly(DocumentType.MD);
        assertThat(result.segments()).hasSize(4);

        ParsedDocumentSegment preface = result.segments().get(0);
        assertThat(preface.text()).isEqualTo("서문\n");
        assertThat(preface.sectionTitle()).isNull();
        assertThat(preface.metadataJson()).isNull();

        assertSection(result.segments().get(1), "환불 정책", List.of("환불 정책"), 1);
        assertThat(result.segments().get(1).text()).isEqualTo("# 환불 정책\n정책 개요");
        assertSection(result.segments().get(2), "개봉 후 환불", List.of("환불 정책", "개봉 후 환불"), 2);
        assertThat(result.segments().get(2).text()).isEqualTo("## 개봉 후 환불\n배송비를 공제한다.");
        assertSection(result.segments().get(3), "배송", List.of("배송"), 1);
    }

    @Test
    @DisplayName("Segment를 LF로 이으면 정규화된 원문과 같아 Chunk 복원 계약을 지킨다")
    void parseDocument_segmentsJoinedByLineFeedRestoreOriginalText() {
        String source = "\n\n서문\n\n# A\n## B\n\n본문 B\n\n\n### C\n```\n# 코드\n```\n# D\n";

        ParsedDocument result = parse(source);

        String restored = String.join("\n", result.segments().stream().map(ParsedDocumentSegment::text).toList());
        assertThat(restored).isEqualTo(source);
    }

    @Test
    @DisplayName("본문이 없는 Heading은 다음 Heading의 Segment에 합쳐 Heading만 있는 Segment를 만들지 않는다")
    void parseDocument_mergesHeadingWithoutBodyIntoNextSection() {
        ParsedDocument result = parse("# 환불 정책\n## 개봉 후 환불\n배송비를 공제한다.");

        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.text()).isEqualTo("# 환불 정책\n## 개봉 후 환불\n배송비를 공제한다.");
            assertSection(segment, "개봉 후 환불", List.of("환불 정책", "개봉 후 환불"), 2);
        });
    }

    @Test
    @DisplayName("문서 끝의 본문 없는 Heading은 원문 보존을 위해 별도 Segment로 둔다")
    void parseDocument_keepsTrailingHeadingWithoutBody() {
        ParsedDocument result = parse("본문\n# 끝");

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().get(1).text()).isEqualTo("# 끝");
        assertSection(result.segments().get(1), "끝", List.of("끝"), 1);
    }

    @Test
    @DisplayName("코드 블록 안의 # 줄은 Heading으로 보지 않는다")
    void parseDocument_ignoresHashLinesInsideCodeFence() {
        ParsedDocument result = parse("# 설치\n```bash\n# 주석입니다\n```\n설명\n~~~\n# 물결 블록\n~~~");

        assertThat(result.segments()).singleElement().satisfies(segment ->
            assertSection(segment, "설치", List.of("설치"), 1));
    }

    @Test
    @DisplayName("닫는 #과 번호형 제목을 정리하고 Heading이 아닌 # 표기는 본문으로 둔다")
    void parseDocument_normalizesHeadingTextAndRejectsNonHeadings() {
        ParsedDocument result = parse("## 3.2 개봉 후 환불 ##\n본문\n#해시태그\n####### 일곱 개\n# C#\n본문 둘");

        assertThat(result.segments()).hasSize(2);
        assertSection(result.segments().get(0), "3.2 개봉 후 환불", List.of("3.2 개봉 후 환불"), 2);
        assertThat(result.segments().get(0).text()).contains("#해시태그").contains("####### 일곱 개");
        assertSection(result.segments().get(1), "C#", List.of("C#"), 1);
    }

    @Test
    @DisplayName("닫는 #만 있는 빈 제목은 Heading이 아니라 본문 줄로 본다")
    void parseDocument_treatsEmptyHeadingWithClosingHashesAsBody() {
        for (String source : new String[] {"# #\n본문", "## ##\n본문", "# # #\n본문"}) {
            ParsedDocument result = parse(source);

            assertThat(result.segments()).as(source).singleElement().satisfies(segment -> {
                assertThat(segment.text()).isEqualTo(source);
                assertThat(segment.sectionTitle()).isNull();
                assertThat(segment.metadataJson()).isNull();
            });
        }

        ParsedDocument nested = parse("# 제목\n## ##\n본문");

        assertThat(nested.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.text()).isEqualTo("# 제목\n## ##\n본문");
            assertSection(segment, "제목", List.of("제목"), 1);
        });
    }

    @Test
    @DisplayName("레벨을 건너뛴 Heading도 상위 Heading 아래에 둔다")
    void parseDocument_handlesSkippedHeadingLevel() {
        ParsedDocument result = parse("# 장\n장 본문\n### 세부\n세부 본문");

        assertThat(result.segments()).hasSize(2);
        assertSection(result.segments().get(1), "세부", List.of("장", "세부"), 3);
    }

    @Test
    @DisplayName("Heading이 없는 Markdown은 경로 없는 단일 Segment가 된다")
    void parseDocument_returnsSingleSegmentWithoutHeadings() {
        ParsedDocument result = parse("그냥 본문\n둘째 줄");

        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.text()).isEqualTo("그냥 본문\n둘째 줄");
            assertThat(segment.sectionTitle()).isNull();
            assertThat(segment.metadataJson()).isNull();
        });
    }

    @Test
    @DisplayName("빈 문서와 잘못된 UTF-8은 TXT와 같은 오류 계약을 따른다")
    void parseDocument_propagatesTextParserErrors() {
        assertThatThrownBy(() -> parser.parseDocument(" \n".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DOCUMENT_CONTENT_EMPTY));
        assertThatThrownBy(() -> parser.parseDocument(new byte[] {(byte) 0xC3, (byte) 0x28}))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_TEXT_DECODING_FAILED));
    }

    private ParsedDocument parse(String source) {
        return parser.parseDocument(source.getBytes(StandardCharsets.UTF_8));
    }

    private void assertSection(ParsedDocumentSegment segment, String title, List<String> path, int level) {
        assertThat(segment.sectionTitle()).isEqualTo(title);
        assertThat(segment.metadataJson()).isEqualTo(SectionPath.toMetadataJson(path, level));
    }
}
