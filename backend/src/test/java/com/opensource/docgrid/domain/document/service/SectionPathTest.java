package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Section 경로 Metadata JSON의 직렬화·읽기, 응답 표기와 Embedding 입력 구성을 검증한다.
 */
@DisplayName("SectionPath 테스트")
class SectionPathTest {

    private static final String PATH_JSON =
        "{\"headingPath\":[\"3. 환불 정책\",\"3.2 개봉 후 환불\"],\"headingLevel\":2}";

    @Test
    @DisplayName("경로와 레벨을 고정된 JSON으로 저장하고 다시 읽는다")
    void toMetadataJson_roundTrips() {
        String json = SectionPath.toMetadataJson(List.of("3. 환불 정책", "3.2 개봉 후 환불"), 2);

        assertThat(json).isEqualTo(PATH_JSON);
        assertThat(SectionPath.readHeadings(json)).contains(List.of("3. 환불 정책", "3.2 개봉 후 환불"));
    }

    @Test
    @DisplayName("따옴표와 줄바꿈이 있는 Heading도 유효한 JSON으로 저장한다")
    void toMetadataJson_escapesSpecialCharacters() {
        List<String> headings = List.of("\"인용\" 정책", "줄\\바꿈");

        assertThat(SectionPath.readHeadings(SectionPath.toMetadataJson(headings, 2))).contains(headings);
    }

    @Test
    @DisplayName("경로가 없거나 형식이 다른 Metadata는 경로 없음으로 취급한다")
    void readHeadings_returnsEmptyForUnusableMetadata() {
        assertThat(SectionPath.readHeadings(null)).isEmpty();
        assertThat(SectionPath.readHeadings(" ")).isEmpty();
        assertThat(SectionPath.readHeadings("not json")).isEmpty();
        assertThat(SectionPath.readHeadings("{\"other\":1}")).isEmpty();
        assertThat(SectionPath.readHeadings("{\"headingPath\":\"문자열\"}")).isEmpty();
        assertThat(SectionPath.readHeadings("{\"headingPath\":[]}")).isEmpty();
        assertThat(SectionPath.readHeadings("{\"headingPath\":[\"A\",1]}")).isEmpty();
        assertThat(SectionPath.readHeadings("{\"headingPath\":[\"A\",\" \"]}")).isEmpty();
    }

    @Test
    @DisplayName("표기는 경로를 우선하고 없으면 저장된 Section Title로 대체한다")
    void display_prefersPathAndFallsBackToTitle() {
        assertThat(SectionPath.display(PATH_JSON, "3.2 개봉 후 환불")).isEqualTo("3. 환불 정책 > 3.2 개봉 후 환불");
        assertThat(SectionPath.display(null, "3.2 개봉 후 환불")).isEqualTo("3.2 개봉 후 환불");
        assertThat(SectionPath.display("not json", "3.2 개봉 후 환불")).isEqualTo("3.2 개봉 후 환불");
        assertThat(SectionPath.display(null, null)).isNull();
    }

    @Test
    @DisplayName("Embedding 입력은 경로가 있으면 본문 앞에 붙이고 없으면 본문을 그대로 쓴다")
    void embeddingInput_prependsPathOnlyWhenPresent() {
        assertThat(SectionPath.embeddingInput(PATH_JSON, "본문입니다."))
            .isEqualTo("3. 환불 정책 > 3.2 개봉 후 환불\n본문입니다.");
        assertThat(SectionPath.embeddingInput(null, "본문입니다.")).isEqualTo("본문입니다.");
        assertThat(SectionPath.embeddingInput("not json", "본문입니다.")).isEqualTo("본문입니다.");
    }

    @Test
    @DisplayName("경로가 너무 길면 상위 Heading부터 버려 하위 Heading을 보존한다")
    void embeddingInput_dropsAncestorsWhenPathTooLong() {
        String ancestor = "상".repeat(150);
        String leaf = "하".repeat(100);
        String json = SectionPath.toMetadataJson(List.of(ancestor, leaf), 2);

        assertThat(SectionPath.embeddingInput(json, "본문")).isEqualTo(leaf + "\n본문");
    }

    @Test
    @DisplayName("마지막 Heading 하나만으로도 길면 그 Heading을 상한까지 자른다")
    void embeddingInput_truncatesSingleOverlongHeading() {
        String json = SectionPath.toMetadataJson(List.of("가".repeat(500)), 1);

        assertThat(SectionPath.embeddingInput(json, "본문")).isEqualTo("가".repeat(200) + "\n본문");
    }

    @Test
    @DisplayName("보충 문자(이모지)가 상한 경계에 있어도 중간에서 자르지 않는다")
    void embeddingInput_truncatesByCodePoint() {
        String json = SectionPath.toMetadataJson(List.of("😀".repeat(300)), 1);

        String input = SectionPath.embeddingInput(json, "본문");

        assertThat(input).isEqualTo("😀".repeat(200) + "\n본문");
    }
}
