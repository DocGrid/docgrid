package com.opensource.docgrid.domain.document.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.enums.DocumentType;

import lombok.RequiredArgsConstructor;

/**
 * Markdown 원본을 ATX Heading({@code #} ~ {@code ######}) 기준 Section Segment로 변환한다.
 *
 * <p>디코딩·BOM·줄바꿈 정규화는 {@link TextDocumentParser}에 맡기고, 정규화된 Text를 줄 단위로
 * 나누어 Heading마다 새 Segment를 시작한다. 모든 줄을 그대로 보존해 Segment를 LF로 이으면 원문이
 * 복원되어야 하므로(문서 본문 보기의 Chunk 복원 계약) 줄을 버리거나 다듬지 않는다.
 *
 * <p>코드 블록 안의 {@code #}은 Heading으로 보지 않는다. Setext Heading({@code ===}, {@code ---}),
 * HTML Heading과 들여쓰기 코드 블록은 인식하지 않는다. 본문이 없는 Heading은 다음 Heading의
 * Segment에 합쳐 Heading 한 줄짜리 Chunk가 생기지 않게 한다.
 */
@Component
@RequiredArgsConstructor
public class MarkdownDocumentParser implements DocumentContentParser {

    private static final Set<DocumentType> SUPPORTED_TYPES = Set.of(DocumentType.MD);
    private static final Pattern ATX_HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    private final TextDocumentParser textDocumentParser;

    /**
     * 이 Parser가 Markdown 문서만 처리함을 Registry에 알린다.
     */
    @Override
    public Set<DocumentType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * Markdown Canonical Text를 Heading 경계의 Section Segment 목록으로 변환한다.
     */
    @Override
    public ParsedDocument parseDocument(byte[] content) {
        String[] lines = textDocumentParser.parse(content).split("\n", -1);
        List<ParsedDocumentSegment> segments = new ArrayList<>();
        HeadingTrail headingTrail = new HeadingTrail();
        List<String> sectionLines = new ArrayList<>();
        boolean sectionHasBody = false;
        String sectionTitle = null;
        String openFence = null;

        for (String line : lines) {
            // 1. 코드 블록 안의 줄은 Heading 판정 없이 본문으로 이어 붙이고 닫는 Fence를 찾는다.
            if (openFence != null) {
                sectionLines.add(line);
                sectionHasBody = true;
                if (closesFence(line, openFence)) {
                    openFence = null;
                }
                continue;
            }
            Matcher fence = CODE_FENCE.matcher(line);
            if (fence.matches()) {
                openFence = fence.group(1);
                sectionLines.add(line);
                sectionHasBody = true;
                continue;
            }

            // 2. Heading은 본문이 있는 이전 Section을 닫고, 본문이 없으면 그 줄들을 새 Section에 합친다.
            Matcher heading = ATX_HEADING.matcher(line);
            if (heading.matches() && hasHeadingText(heading.group(2))) {
                if (sectionHasBody) {
                    addSegment(segments, sectionLines, sectionTitle, headingTrail.metadataJson());
                    sectionLines.clear();
                    sectionHasBody = false;
                }
                sectionTitle = heading.group(2).strip();
                headingTrail.push(heading.group(1).length(), sectionTitle);
                sectionLines.add(line);
                continue;
            }

            // 3. 그 밖의 줄은 현재 Section에 그대로 추가하고 공백이 아닌 줄이 있으면 본문으로 본다.
            sectionLines.add(line);
            if (!line.isBlank()) {
                sectionHasBody = true;
            }
        }

        // 4. 마지막 Section을 닫는다. 본문이 없는 끝 Heading도 원문 보존을 위해 별도 Segment로 둔다.
        addSegment(segments, sectionLines, sectionTitle, headingTrail.metadataJson());
        return new ParsedDocument(segments);
    }

    /**
     * 제목 글자가 실제로 있는지 확인한다. 비었거나 닫는 {@code #}만 남은 줄({@code # #})은 CommonMark에서 빈 제목이다.
     */
    private boolean hasHeadingText(String text) {
        String stripped = text.strip();
        return !stripped.isEmpty() && !stripped.chars().allMatch(character -> character == '#');
    }

    /**
     * 열린 Fence와 같은 문자로 같거나 더 길게 이어진, 정보 문자열 없는 줄인지 확인한다.
     */
    private boolean closesFence(String line, String openFence) {
        Matcher fence = CODE_FENCE.matcher(line);
        if (!fence.matches()) {
            return false;
        }
        String marker = fence.group(1);
        return marker.charAt(0) == openFence.charAt(0)
            && marker.length() >= openFence.length()
            && fence.group(2).isBlank();
    }

    /**
     * 모은 줄을 LF로 이어 Segment로 추가한다. 공백뿐인 줄 모음은 Segment로 만들 수 없어 건너뛴다.
     */
    private void addSegment(
        List<ParsedDocumentSegment> segments,
        List<String> sectionLines,
        String sectionTitle,
        String metadataJson
    ) {
        String text = String.join("\n", sectionLines);
        if (!text.isBlank()) {
            segments.add(new ParsedDocumentSegment(text, null, sectionTitle, metadataJson));
        }
    }
}
