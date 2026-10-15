package com.opensource.docgrid.domain.document.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor;
import kr.dogfoot.hwpxlib.tool.textextractor.TextMarks;

/**
 * HWPX ZIP·XML을 제한적으로 검증한 뒤 본문과 표 셀 텍스트를 추출한다.
 *
 * <p>라이브러리가 파일 경로를 요구하므로 임시 원본을 사용하고 반드시 삭제한다. OCR, 화면 배치와
 * Chunk 계산은 담당하지 않는다.
 */
@Component
public class HwpxDocumentParser implements DocumentContentParser {

    private static final Set<DocumentType> SUPPORTED_TYPES = Set.of(DocumentType.HWPX);
    private static final Logger log = LoggerFactory.getLogger(HwpxDocumentParser.class);
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 100L * 1024 * 1024;
    private static final String HWPX_MIMETYPE = "application/hwp+zip";

    @Override
    public Set<DocumentType> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * ZIP 폭탄과 외부 XML 개체를 차단한 뒤 원래 문단·표 셀 순서의 검색 텍스트를 만든다.
     */
    @Override
    public ParsedDocument parseDocument(byte[] content) {
        if (content == null || content.length == 0) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
        }

        Path temporaryFile = null;
        try {
            // 1. hwpxlib의 File API를 위해 임시 파일을 만들고 라이브러리 호출 전에 ZIP·XML을 검사한다.
            temporaryFile = Files.createTempFile("docgrid-hwpx-", ".hwpx");
            Files.write(temporaryFile, content);
            inspectArchive(temporaryFile);

            // 2. DTD가 없는 정상 문서만 라이브러리로 읽고 표 셀을 문단 사이에 펼친다.
            HWPXFile file = HWPXReader.fromFile(temporaryFile.toFile());
            TextMarks marks = new TextMarks()
                .paraSeparatorAnd("\n")
                .lineBreakAnd("\n")
                .tabAnd("\t")
                .tableStartAnd("\n")
                .tableEndAnd("\n")
                .tableRowSeparatorAnd("\n")
                .tableCellSeparatorAnd("\t");
            String text = TextExtractor.extract(
                file, TextExtractMethod.InsertControlTextBetweenParagraphText, false, marks
            ).replace("\r\n", "\n").replace('\r', '\n').strip();
            if (text.isBlank()) {
                throw new DocGridException(ErrorCode.DOCUMENT_CONTENT_EMPTY);
            }

            // 3. 레이아웃 기반 페이지 번호는 만들지 않고 텍스트만 기존 Chunker에 전달한다.
            return new ParsedDocument(List.of(new ParsedDocumentSegment(text, null, null, null)));
        } catch (DocGridException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED, exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException exception) {
                    // 실패한 임시 파일은 노출하지 않되 운영 로그에 정리 실패 사실은 남긴다.
                    log.warn("HWPX 임시 파일 정리에 실패했습니다.");
                }
            }
        }
    }

    /**
     * 라이브러리가 기본 SAX 설정으로 읽기 전에 제한을 적용한다.
     */
    private void inspectArchive(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (!hasValidMimetype(zip)) {
                throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
            }

            int entryCount = 0;
            long totalBytes = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (++entryCount > MAX_ENTRIES || entry.getSize() > MAX_ENTRY_BYTES) {
                    throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
                }
                // 실제로 해제한 바이트로 검사해 크기 미기재 엔트리도 제한한다.
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = input.readNBytes(MAX_ENTRY_BYTES + 1);
                }
                totalBytes += bytes.length;
                if (bytes.length > MAX_ENTRY_BYTES || totalBytes > MAX_ARCHIVE_BYTES) {
                    throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
                }
                if (isXml(entry.getName(), bytes)) {
                    validateSafeXml(bytes, entry.getName());
                }
            }
        }
    }

    private boolean hasValidMimetype(ZipFile zip) throws IOException {
        ZipEntry mimetype = zip.getEntry("mimetype");
        if (mimetype == null || mimetype.getSize() > 128) {
            return false;
        }
        try (var input = zip.getInputStream(mimetype)) {
            return HWPX_MIMETYPE.equals(new String(input.readNBytes(129), StandardCharsets.UTF_8).trim());
        }
    }

    private boolean isXml(String name, byte[] bytes) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".xml") || normalizedName.endsWith(".hpf")) {
            return true;
        }
        int index = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb
            && (bytes[2] & 0xff) == 0xbf) {
            index = 3;
        }
        while (index < bytes.length && (bytes[index] == ' ' || bytes[index] == '\n')) {
            index++;
        }
        // Preview/PrvText.txt도 '<'로 시작할 수 있으므로 XML 선언이 있는 항목만 추가 검사한다.
        return index + 4 < bytes.length && bytes[index] == '<' && bytes[index + 1] == '?'
            && (bytes[index + 2] == 'x' || bytes[index + 2] == 'X')
            && (bytes[index + 3] == 'm' || bytes[index + 3] == 'M')
            && (bytes[index + 4] == 'l' || bytes[index + 4] == 'L');
    }

    /**
     * XML을 재파싱해 DTD·외부 개체 사용이 가능하면 HWPX 전체를 거부한다.
     */
    private void validateSafeXml(byte[] bytes, String name) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        SAXParser parser = factory.newSAXParser();
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        parser.parse(new ByteArrayInputStream(bytes), new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
                // 암호화된 HWPX 항목은 본문 추출 결과를 신뢰할 수 없으므로 정상 문서로 처리하지 않는다.
                if ("META-INF/manifest.xml".equals(name) && "encryption-data".equals(localName)) {
                    throw new SAXException("암호화된 HWPX는 지원하지 않습니다.");
                }
                // hwpxlib가 manifest 참조로 추가 XML을 읽을 수 있으므로 검사하지 않은 확장자는 거부한다.
                if (name.endsWith(".hpf") && "item".equals(localName)
                    && "application/xml".equals(attribute(attributes, "media-type"))
                    && !hasXmlExtension(attribute(attributes, "href"))) {
                    throw new SAXException("검증하지 않은 XML 항목은 지원하지 않습니다.");
                }
                if ("META-INF/container.xml".equals(name) && "rootfile".equals(localName)
                    && "application/hwpml-package+xml".equals(attribute(attributes, "media-type"))
                    && !hasXmlExtension(attribute(attributes, "full-path"))) {
                    throw new SAXException("검증하지 않은 패키지 XML은 지원하지 않습니다.");
                }
            }
        });
    }

    private boolean hasXmlExtension(String path) {
        if (path == null) {
            return false;
        }
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".xml") || normalized.endsWith(".hpf");
    }

    private String attribute(Attributes attributes, String localName) {
        for (int index = 0; index < attributes.getLength(); index++) {
            if (localName.equals(attributes.getLocalName(index))) {
                return attributes.getValue(index);
            }
        }
        return null;
    }
}
