package com.opensource.docgrid.domain.document.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * Apache POI로 DOCX의 Heading, 본문 Paragraph와 Table을 원본 Body 순서대로 추출한다.
 *
 * <p>Heading을 Section 경계로 사용하지만 Header, Footer, Comment와 이미지 OCR은 처리하지 않는다.
 */
@Slf4j
@Component
public class DocxDocumentParser implements DocumentContentParser {

    private static final Set<DocumentType> SUPPORTED_TYPES = Set.of(DocumentType.DOCX);
    private static final Pattern HEADING_LEVEL_PATTERN = Pattern.compile("\\d+");
    private static final int TITLE_LEVEL = 0;
    private static final int DEFAULT_HEADING_LEVEL = 1;

    /**
     * 이 Parser가 DOCX 문서만 처리함을 Registry에 알린다.
     */
    @Override
    public Set<DocumentType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * DOCX Body의 검색 가능한 Text를 Section Segment 목록으로 변환한다.
     */
    @Override
    public ParsedDocument parseDocument(byte[] content) {
        if (content == null) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
        }

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<ParsedDocumentSegment> segments = new ArrayList<>();
            List<String> sectionParts = new ArrayList<>();
            HeadingTrail headingTrail = new HeadingTrail();
            String sectionTitle = null;

            // 1. Paragraph와 Table을 종류별 목록으로 분리하지 않고 Body 요소의 원래 순서를 따른다.
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph paragraph) {
                    String paragraphText = canonicalize(paragraph.getText());
                    if (paragraphText.isBlank()) {
                        continue;
                    }

                    // 2. Heading은 이전 Section을 닫고 자신을 첫 줄로 포함하는 새 Section을 시작한다.
                    if (isHeading(paragraph)) {
                        addSegmentIfPresent(segments, sectionParts, sectionTitle, headingTrail.metadataJson());
                        sectionParts.clear();
                        headingTrail.push(headingLevel(paragraph), paragraphText);
                        sectionTitle = paragraphText;
                    }
                    sectionParts.add(paragraphText);
                } else if (bodyElement instanceof XWPFTable table) {
                    String tableText = tableText(table);
                    if (!tableText.isBlank()) {
                        sectionParts.add(tableText);
                    }
                }
            }

            // 3. 마지막 Section을 닫고 검색 가능한 본문이 없으면 안정적인 빈 문서 오류로 종료한다.
            addSegmentIfPresent(segments, sectionParts, sectionTitle, headingTrail.metadataJson());
            if (segments.isEmpty()) {
                throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_EMPTY);
            }
            return new ParsedDocument(segments);
        } catch (DocGridException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            log.error("DOCX 문서 구조를 읽지 못했습니다.", exception);
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED, exception);
        }
    }

    /**
     * Paragraph Style이 Heading 계열 또는 문서 Title인지 판정한다.
     */
    private boolean isHeading(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return false;
        }
        String normalizedStyle = style.toLowerCase(Locale.ROOT);
        return normalizedStyle.startsWith("heading") || normalizedStyle.equals("title");
    }

    /**
     * Heading 스타일에서 계층 레벨을 읽는다. 문서 Title은 모든 Heading의 상위인 0, 숫자가 없는 Heading은 1로 본다.
     */
    private int headingLevel(XWPFParagraph paragraph) {
        String normalizedStyle = paragraph.getStyle().toLowerCase(Locale.ROOT);
        if (normalizedStyle.equals("title")) {
            return TITLE_LEVEL;
        }
        Matcher levelMatcher = HEADING_LEVEL_PATTERN.matcher(normalizedStyle);
        return levelMatcher.find() ? Integer.parseInt(levelMatcher.group()) : DEFAULT_HEADING_LEVEL;
    }

    /**
     * DOCX 표를 행은 LF, 셀은 Tab으로 구분한 검색 가능한 선형 Text로 변환한다.
     */
    private String tableText(XWPFTable table) {
        // 1. 원본 행 순서대로 각 셀 Text를 정규화한다.
        List<String> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = row.getTableCells().stream()
                .map(XWPFTableCell::getText)
                .map(this::canonicalize)
                .toList();
            String rowText = String.join("\t", cells);

            // 2. 전부 빈 셀인 행은 검색 본문에 추가하지 않는다.
            if (!rowText.isBlank()) {
                rows.add(rowText);
            }
        }

        // 3. 남은 행을 LF로 연결해 Paragraph와 같은 Section 일부로 사용할 Text를 만든다.
        return String.join("\n", rows);
    }

    /**
     * 현재 Section에 검색 가능한 본문이 있으면 제목과 계층 경로 Metadata와 함께 Segment로 닫는다.
     */
    private void addSegmentIfPresent(
        List<ParsedDocumentSegment> segments,
        List<String> sectionParts,
        String sectionTitle,
        String metadataJson
    ) {
        if (sectionParts.isEmpty()) {
            return;
        }
        String text = String.join("\n", sectionParts);
        if (!text.isBlank()) {
            segments.add(new ParsedDocumentSegment(text, null, sectionTitle, metadataJson));
        }
    }

    /**
     * POI 추출 Text의 null과 플랫폼별 줄바꿈을 처리하고 요소 가장자리 공백을 제거한다.
     */
    private String canonicalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
