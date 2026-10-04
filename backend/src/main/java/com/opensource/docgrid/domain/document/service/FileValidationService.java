package com.opensource.docgrid.domain.document.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.opensource.docgrid.domain.document.config.DocumentUploadProperties;
import com.opensource.docgrid.domain.document.enums.DocumentType;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 업로드된 파일의 크기, 이름, 확장자와 MIME Type을 애플리케이션 허용 정책에 맞게 검증한다.
 *
 * <p>검증을 통과한 파일은 후속 저장·파싱 계층이 신뢰할 수 있는 {@link ValidatedFile}로 변환한다.
 * 이 서비스는 파일 내용의 악성 여부나 실제 문서 파싱 가능성까지 판정하지 않으며, 저장 전에 확인할
 * 수 있는 메타데이터 경계만 책임진다.
 */
@Service
@RequiredArgsConstructor
public class FileValidationService {

    /**
     * Markdown 업로드에 허용하는 MIME Type이다.
     *
     * <p>브라우저와 OS마다 .md에 붙이는 타입이 달라(text/markdown, text/x-markdown, 타입을 모르면
     * application/octet-stream) 이들을 함께 허용한다. 업로드 때 저장된 타입을 청킹 단계가 다시 검사하므로
     * 두 곳이 어긋나지 않게 이 상수 하나를 공유한다.
     */
    public static final Set<String> MARKDOWN_CONTENT_TYPES = Set.of(
        "text/markdown", "text/x-markdown", "text/plain", "application/octet-stream"
    );

    private static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES = Map.of(
        "txt", Set.of("text/plain"),
        "md", MARKDOWN_CONTENT_TYPES,
        "pdf", Set.of("application/pdf"),
        "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    );
    private static final Map<String, DocumentType> DOCUMENT_TYPES = Map.of(
        "txt", DocumentType.TXT,
        "md", DocumentType.MD,
        "pdf", DocumentType.PDF,
        "docx", DocumentType.DOCX
    );

    private final DocumentUploadProperties documentUploadProperties;

    /**
     * Multipart 파일을 업로드 정책에 따라 검증하고 정규화된 파일 정보를 반환한다.
     *
     * @param file 사용자가 전송한 Multipart 파일
     * @return 안전한 파일명과 확정된 문서 형식을 포함한 검증 결과
     */
    public ValidatedFile validate(MultipartFile file) {
        // 1. 내용이 없거나 설정된 최대 크기를 넘는 파일은 저장소 작업 전에 거부한다.
        if (file == null || file.isEmpty() || file.getSize() == 0) {
            throw new DocGridException(ErrorCode.EMPTY_FILE);
        }
        if (file.getSize() > documentUploadProperties.getMaxFileSize().toBytes()) {
            throw new DocGridException(ErrorCode.FILE_SIZE_EXCEEDED);
        }

        // 2. 파일명을 Unicode NFC로 정규화하고 확장자와 요청 MIME Type을 추출한다.
        String originalFilename = normalizeFilename(file.getOriginalFilename());
        String extension = extractExtension(originalFilename);
        String contentType = file.getContentType();

        // 3. 확장자별 허용 MIME Type을 함께 확인해 지원하지 않는 파일 형식 유입을 막는다.
        Set<String> allowedContentTypes = ALLOWED_CONTENT_TYPES.get(extension);
        if (allowedContentTypes == null) {
            throw new DocGridException(ErrorCode.UNSUPPORTED_FILE_EXTENSION);
        }
        if (contentType == null || !allowedContentTypes.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new DocGridException(ErrorCode.UNSUPPORTED_FILE_CONTENT_TYPE);
        }

        // 4. 검증된 확장자를 내부 DocumentType으로 확정해 후속 계층이 문자열을 재해석하지 않게 한다.
        DocumentType documentType = DOCUMENT_TYPES.get(extension);
        return new ValidatedFile(originalFilename, extension, contentType, file.getSize(), documentType);
    }

    /**
     * 파일명을 NFC 형식으로 정규화하고 경로 이동 또는 제어 문자를 포함한 이름을 거부한다.
     */
    private String normalizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new DocGridException(ErrorCode.INVALID_FILE_NAME);
        }

        String normalized = Normalizer.normalize(filename.trim(), Normalizer.Form.NFC);
        boolean hasControlCharacter = normalized.codePoints().anyMatch(Character::isISOControl);
        if (normalized.isBlank() || hasControlCharacter || normalized.contains("..")
                || normalized.contains("/") || normalized.contains("\\")) {
            throw new DocGridException(ErrorCode.INVALID_FILE_NAME);
        }
        return normalized;
    }

    /**
     * 정규화된 파일명에서 마지막 확장자를 소문자로 추출한다.
     */
    private String extractExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == filename.length() - 1) {
            throw new DocGridException(ErrorCode.UNSUPPORTED_FILE_EXTENSION);
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
