package com.opensource.docgrid.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import com.opensource.docgrid.domain.document.config.DocumentUploadProperties;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@DisplayName("FileValidationService 단위 테스트")
class FileValidationServiceTest {

    private FileValidationService fileValidationService;

    @BeforeEach
    void setUp() {
        DocumentUploadProperties properties = new DocumentUploadProperties();
        properties.setMaxFileSize(DataSize.ofMegabytes(10));
        fileValidationService = new FileValidationService(properties);
    }

    @Test
    @DisplayName("정상 TXT 파일을 검증한다")
    void validate_succeeds_forTextFile() {
        MockMultipartFile file = file("sample.TXT", "text/plain", "hello");

        ValidatedFile result = fileValidationService.validate(file);

        assertThat(result.originalFilename()).isEqualTo("sample.TXT");
        assertThat(result.extension()).isEqualTo("txt");
        assertThat(result.documentType()).isEqualTo(DocumentType.TXT);
    }

    @Test
    @DisplayName("text/plain Markdown 파일을 허용한다")
    void validate_succeeds_forMarkdownWithPlainContentType() {
        ValidatedFile result = fileValidationService.validate(file("README.md", "text/plain", "# title"));

        assertThat(result.documentType()).isEqualTo(DocumentType.MD);
    }

    @Test
    @DisplayName("브라우저가 .md에 붙이는 여러 Content-Type을 모두 허용한다")
    void validate_succeeds_forMarkdownWithBrowserContentTypes() {
        for (String contentType : new String[] {
            "text/markdown", "text/x-markdown", "text/plain", "application/octet-stream"
        }) {
            ValidatedFile result = fileValidationService.validate(file("README.md", contentType, "# title"));

            assertThat(result.documentType()).as(contentType).isEqualTo(DocumentType.MD);
        }
    }

    @Test
    @DisplayName("PDF 확장자와 Content-Type 조합을 허용한다")
    void validate_succeeds_forPdf() {
        ValidatedFile result = fileValidationService.validate(
            file("guide.PDF", "application/pdf", "%PDF".getBytes())
        );

        assertThat(result.extension()).isEqualTo("pdf");
        assertThat(result.documentType()).isEqualTo(DocumentType.PDF);
    }

    @Test
    @DisplayName("DOCX 확장자와 OOXML Content-Type 조합을 허용한다")
    void validate_succeeds_forDocx() {
        ValidatedFile result = fileValidationService.validate(file(
            "guide.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            new byte[] {1}
        ));

        assertThat(result.documentType()).isEqualTo(DocumentType.DOCX);
    }

    @Test
    @DisplayName("빈 파일이면 예외가 발생한다")
    void validate_throws_when_fileIsEmpty() {
        assertError(file("empty.txt", "text/plain", ""), ErrorCode.EMPTY_FILE);
    }

    @Test
    @DisplayName("기본 최대 파일 크기는 50MB다")
    void defaultMaxFileSize_is50Megabytes() {
        DocumentUploadProperties properties = new DocumentUploadProperties();

        assertThat(properties.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(50));
    }

    @Test
    @DisplayName("최대 파일 크기와 같은 파일은 허용한다")
    void validate_succeeds_when_fileSizeEqualsLimit() {
        DocumentUploadProperties properties = new DocumentUploadProperties();
        properties.setMaxFileSize(DataSize.ofBytes(4));
        fileValidationService = new FileValidationService(properties);

        ValidatedFile result = fileValidationService.validate(file("limit.txt", "text/plain", "four"));

        assertThat(result.fileSize()).isEqualTo(4L);
    }

    @Test
    @DisplayName("최대 파일 크기를 초과하면 예외가 발생한다")
    void validate_throws_when_fileIsTooLarge() {
        DocumentUploadProperties properties = new DocumentUploadProperties();
        properties.setMaxFileSize(DataSize.ofBytes(3));
        fileValidationService = new FileValidationService(properties);

        assertError(file("large.txt", "text/plain", "four"), ErrorCode.FILE_SIZE_EXCEEDED);
    }

    @Test
    @DisplayName("지원하지 않는 확장자면 예외가 발생한다")
    void validate_throws_when_extensionIsUnsupported() {
        assertError(file("sample.doc", "application/msword", "doc"), ErrorCode.UNSUPPORTED_FILE_EXTENSION);
    }

    @Test
    @DisplayName("확장자와 Content-Type이 일치하지 않으면 예외가 발생한다")
    void validate_throws_when_contentTypeIsUnsupported() {
        assertError(file("sample.md", "application/pdf", "md"),
            ErrorCode.UNSUPPORTED_FILE_CONTENT_TYPE);
        assertError(file("sample.txt", "application/octet-stream", "txt"),
            ErrorCode.UNSUPPORTED_FILE_CONTENT_TYPE);
        assertError(file("sample.pdf", "application/octet-stream", "pdf"),
            ErrorCode.UNSUPPORTED_FILE_CONTENT_TYPE);
        assertError(file(
            "sample.docx",
            "application/pdf",
            "docx"
        ), ErrorCode.UNSUPPORTED_FILE_CONTENT_TYPE);
    }

    @Test
    @DisplayName("경로 문자가 포함된 파일명이면 예외가 발생한다")
    void validate_throws_when_filenameContainsPath() {
        assertError(file("../sample.txt", "text/plain", "text"), ErrorCode.INVALID_FILE_NAME);
        assertError(file("dir\\sample.txt", "text/plain", "text"), ErrorCode.INVALID_FILE_NAME);
    }

    @Test
    @DisplayName("제어 문자가 포함된 파일명이면 예외가 발생한다")
    void validate_throws_when_filenameContainsControlCharacter() {
        assertError(file("sample\u0000.txt", "text/plain", "text"), ErrorCode.INVALID_FILE_NAME);
    }

    private MockMultipartFile file(String filename, String contentType, String content) {
        return file(filename, contentType, content.getBytes());
    }

    private MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    private void assertError(MockMultipartFile file, ErrorCode errorCode) {
        assertThatThrownBy(() -> fileValidationService.validate(file))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", errorCode);
    }
}
