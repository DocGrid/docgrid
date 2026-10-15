package com.opensource.docgrid.domain.document.service;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractOption;
import kr.dogfoot.hwplib.tool.textextractor.TextExtractor;

/**
 * HWP 5.x 원본의 본문과 표 셀 텍스트만 추출해 기존 Chunker에 전달한다.
 *
 * <p>원본 저장, OCR, 화면 배치 및 Chunk 계산은 담당하지 않는다.
 */
@Component
public class HwpDocumentParser implements DocumentContentParser {

    private static final Set<DocumentType> SUPPORTED_TYPES = Set.of(DocumentType.HWP);

    @Override
    public Set<DocumentType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * HWP 문단 순서 안에 표 컨트롤 텍스트를 삽입한 단일 Segment를 만든다.
     */
    @Override
    public ParsedDocument parseDocument(byte[] content) {
        if (content == null || content.length == 0) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
        }

        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            // 1. HWP 5.x 구조를 읽으며 암호·손상 파일은 정상 본문으로 처리하지 않는다.
            HWPFile file = HWPReader.fromInputStream(input);
            if (file.getFileHeader().getVersion().getMM() != 5 || file.getFileHeader().hasPassword()) {
                throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
            }

            // 2. 표 셀을 포함한 컨트롤 텍스트를 문단 사이의 실제 위치에 삽입한다.
            TextExtractOption option = new TextExtractOption(
                TextExtractMethod.InsertControlTextBetweenParagraphText
            );
            option.setInsertParaHead(false);
            String text = TextExtractor.extract(file, option)
                .replace("\r\n", "\n").replace('\r', '\n').strip();
            if (text.isBlank()) {
                throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_EMPTY);
            }

            // 3. HWP 5.x는 이 라이브러리에서 신뢰할 페이지 경계를 제공하지 않는다.
            return new ParsedDocument(List.of(new ParsedDocumentSegment(text, null, null, null)));
        } catch (DocGridException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED, exception);
        }
    }
}
