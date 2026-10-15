package com.opensource.docgrid.domain.document.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

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
 * HWP·HWPX는 범용 MIME도 허용하므로 업로드 시 컨테이너 서명과 문서 식별자를 확인한다.
 * 전체 본문 파싱과 형식별 세부 구조 검사는 Worker의 Parser가 맡는다.
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
    public static final Set<String> HWP_CONTENT_TYPES = Set.of(
        "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp",
        "application/octet-stream"
    );
    public static final Set<String> HWPX_CONTENT_TYPES = Set.of(
        "application/hwp+zip", "application/vnd.hancom.hwpx", "application/zip",
        "application/octet-stream"
    );
    private static final byte[] OLE_SIGNATURE = {
        (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 3, 4};
    private static final byte[] HWP_SIGNATURE = "HWP Document File".getBytes(StandardCharsets.US_ASCII);
    private static final int HWP_HEADER_LENGTH = 40;
    private static final int MAX_HWPX_MIMETYPE_SEARCH_BYTES = 1024 * 1024;
    private static final int MAX_HWPX_MIMETYPE_SEARCH_ENTRIES = 32;
    private static final String HWPX_MIMETYPE = "application/hwp+zip";

    private static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES = Map.of(
        "txt", Set.of("text/plain"),
        "md", MARKDOWN_CONTENT_TYPES,
        "pdf", Set.of("application/pdf"),
        "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        "hwp", HWP_CONTENT_TYPES,
        "hwpx", HWPX_CONTENT_TYPES
    );
    private static final Map<String, DocumentType> DOCUMENT_TYPES = Map.of(
        "txt", DocumentType.TXT,
        "md", DocumentType.MD,
        "pdf", DocumentType.PDF,
        "docx", DocumentType.DOCX,
        "hwp", DocumentType.HWP,
        "hwpx", DocumentType.HWPX
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

        // 4. 범용 MIME도 허용하는 HWP·HWPX는 컨테이너의 실제 문서 식별자를 저장 전에 확인한다.
        if ("hwp".equals(extension)) {
            validateHwpStructure(file);
        } else if ("hwpx".equals(extension)) {
            validateHwpxStructure(file);
        }

        // 5. 검증된 확장자를 내부 DocumentType으로 확정해 후속 계층이 문자열을 재해석하지 않게 한다.
        DocumentType documentType = DOCUMENT_TYPES.get(extension);
        return new ValidatedFile(originalFilename, extension, contentType, file.getSize(), documentType);
    }

    /**
     * OLE 컨테이너의 FileHeader가 HWP 5.x인지 검사한다. 전체 문단 구조 검사는 Parser에 남긴다.
     */
    private void validateHwpStructure(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            if (!Arrays.equals(input.readNBytes(OLE_SIGNATURE.length), OLE_SIGNATURE)) {
                throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
            }
        } catch (IOException exception) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED, exception);
        }

        try (InputStream input = file.getInputStream();
             POIFSFileSystem ole = new POIFSFileSystem(input);
             DocumentInputStream header = ole.createDocumentInputStream("FileHeader")) {
            byte[] bytes = header.readNBytes(HWP_HEADER_LENGTH);
            if (bytes.length != HWP_HEADER_LENGTH
                || !Arrays.equals(Arrays.copyOf(bytes, HWP_SIGNATURE.length), HWP_SIGNATURE)
                || Byte.toUnsignedInt(bytes[35]) != 5
                || (bytes[36] & 0x02) != 0) {
                throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
            }
        } catch (DocGridException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED, exception);
        }
    }

    /**
     * ZIP 내부의 HWPX mimetype을 제한된 범위에서 확인해 위장된 범용 ZIP을 거부한다.
     */
    private void validateHwpxStructure(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            if (!Arrays.equals(input.readNBytes(ZIP_SIGNATURE.length), ZIP_SIGNATURE)) {
                throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
            }
        } catch (IOException exception) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED, exception);
        }

        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            int inspectedBytes = 0;
            for (int entryIndex = 0; entryIndex < MAX_HWPX_MIMETYPE_SEARCH_ENTRIES; entryIndex++) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) {
                    break;
                }
                // mimetype 이전의 큰 항목을 끝까지 해제하지 않아 ZIP 폭탄을 업로드에서 차단한다.
                byte[] bytes = zip.readNBytes(MAX_HWPX_MIMETYPE_SEARCH_BYTES - inspectedBytes + 1);
                inspectedBytes += bytes.length;
                if (inspectedBytes > MAX_HWPX_MIMETYPE_SEARCH_BYTES) {
                    break;
                }
                if ("mimetype".equals(entry.getName())
                    && HWPX_MIMETYPE.equals(new String(bytes, StandardCharsets.UTF_8).trim())) {
                    return;
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED, exception);
        }
        throw new DocGridException(ErrorCode.DOCUMENT_PARSING_FAILED);
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
