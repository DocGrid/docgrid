package com.opensource.docgrid.domain.document.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Heading 계층 경로를 Chunk의 {@code metadata_json}, Embedding 입력, 응답 표기로 변환한다.
 *
 * <p>경로는 상위에서 하위 순서의 Heading 문구 목록이며 {@code {"headingPath":[...],"headingLevel":n}}
 * 형태의 JSON으로 저장한다. Heading 판정과 Chunk 계산은 담당하지 않고, 저장된 값의 직렬화와
 * 읽기·표기만 맡는다. 경로가 없거나 읽을 수 없는 Metadata는 모두 "경로 없음"으로 취급한다.
 */
public final class SectionPath {

    private static final String HEADING_PATH_KEY = "headingPath";
    private static final String HEADING_LEVEL_KEY = "headingLevel";
    private static final String SEPARATOR = " > ";
    private static final int MAX_EMBEDDING_PATH_CODE_POINTS = 200;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SectionPath() {
    }

    /**
     * 상위→하위 Heading 목록과 마지막 Heading의 레벨을 Metadata JSON 문자열로 만든다.
     */
    public static String toMetadataJson(List<String> headings, int headingLevel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(HEADING_PATH_KEY, headings);
        payload.put(HEADING_LEVEL_KEY, headingLevel);
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Section 경로 Metadata를 JSON으로 만들지 못했습니다.", exception);
        }
    }

    /**
     * Metadata JSON에서 Heading 경로를 읽는다. 값이 없거나 형식이 다르면 빈 값을 반환한다.
     */
    public static Optional<List<String>> readHeadings(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode headingPath = OBJECT_MAPPER.readTree(metadataJson).get(HEADING_PATH_KEY);
            if (headingPath == null || !headingPath.isArray() || headingPath.isEmpty()) {
                return Optional.empty();
            }
            List<String> headings = new ArrayList<>(headingPath.size());
            for (JsonNode heading : headingPath) {
                if (!heading.isTextual() || heading.asText().isBlank()) {
                    return Optional.empty();
                }
                headings.add(heading.asText());
            }
            return Optional.of(List.copyOf(headings));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    /**
     * 응답에 표시할 Section 경로를 만든다.
     *
     * <p>Metadata 경로가 있으면 "상위 > 하위"로 잇고, 없으면 저장된 Section Title을 그대로 쓴다.
     * 경로 Metadata 도입 전에 만들어진 DOCX Chunk도 마지막 Heading은 보여주기 위한 대체 값이다.
     *
     * @return 표시할 경로, 둘 다 없으면 null
     */
    public static String display(String metadataJson, String sectionTitle) {
        return readHeadings(metadataJson)
            .map(headings -> String.join(SEPARATOR, headings))
            .orElse(sectionTitle);
    }

    /**
     * Chunk 본문 앞에 Section 경로를 붙인 Embedding 입력을 만든다.
     *
     * <p>Metadata 경로가 없으면 본문을 그대로 반환해 PDF·TXT와 경로 도입 전 Chunk의 입력을 바꾸지 않는다.
     * 저장되는 {@code chunk_text}에는 영향을 주지 않으며 Vector를 만들 때만 쓰는 값이다.
     */
    public static String embeddingInput(String metadataJson, String chunkText) {
        return readHeadings(metadataJson)
            .map(headings -> limitForEmbedding(headings) + "\n" + chunkText)
            .orElse(chunkText);
    }

    /**
     * 비정상적으로 긴 Heading이 입력 예산을 잠식하지 않도록 경로 길이를 제한한다.
     *
     * <p>상위 Heading부터 버려 가장 구체적인 하위 Heading을 우선 보존하고, 마지막 Heading 하나만으로도
     * 길면 그 Heading을 잘라낸다.
     */
    private static String limitForEmbedding(List<String> headings) {
        int start = 0;
        String joined = String.join(SEPARATOR, headings);
        while (codePointLength(joined) > MAX_EMBEDDING_PATH_CODE_POINTS && start < headings.size() - 1) {
            start++;
            joined = String.join(SEPARATOR, headings.subList(start, headings.size()));
        }
        if (codePointLength(joined) > MAX_EMBEDDING_PATH_CODE_POINTS) {
            int end = joined.offsetByCodePoints(0, MAX_EMBEDDING_PATH_CODE_POINTS);
            return joined.substring(0, end);
        }
        return joined;
    }

    private static int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }
}
