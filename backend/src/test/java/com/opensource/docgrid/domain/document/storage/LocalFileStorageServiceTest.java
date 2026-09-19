package com.opensource.docgrid.domain.document.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Local Filesystem Adapter의 저장·조회·삭제와 Root 경로 격리 계약을 검증한다.
 */
@DisplayName("LocalFileStorageService 테스트")
class LocalFileStorageServiceTest {

    private static final String BUCKET = "local-test";

    @TempDir
    private Path temporaryRoot;

    @TempDir
    private Path outsideRoot;

    private LocalFileStorageService storageService;

    @BeforeEach
    void setUp() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setBucket(BUCKET);
        properties.getLocal().setRoot(temporaryRoot);
        storageService = new LocalFileStorageService(properties);
    }

    @Test
    @DisplayName("Root 아래에 파일을 저장하고 같은 위치에서 읽은 뒤 삭제한다")
    void storeReadDelete_usesConfiguredRoot() {
        byte[] content = "DocGrid Local 원본".getBytes(StandardCharsets.UTF_8);

        StoredFile storedFile = storageService.store(
            new ByteArrayInputStream(content),
            content.length,
            "text/plain",
            "documents/source.txt"
        );

        assertThat(storedFile).isEqualTo(
            new StoredFile(StorageProvider.LOCAL, BUCKET, "documents/source.txt")
        );
        assertThat(storageService.read(storedFile)).isEqualTo(content);
        assertThat(temporaryRoot.resolve(storedFile.objectKey())).hasBinaryContent(content);

        storageService.delete(storedFile);

        assertThat(temporaryRoot.resolve(storedFile.objectKey())).doesNotExist();
    }

    @Test
    @DisplayName("접두사 아래 일반 파일만 논리 Object Metadata로 조회한다")
    void streamObjects_listsFilesUnderPrefix() throws Exception {
        Path document = temporaryRoot.resolve("documents/a/source.txt");
        Path outside = temporaryRoot.resolve("other/ignored.txt");
        Files.createDirectories(document.getParent());
        Files.createDirectories(outside.getParent());
        Files.writeString(document, "source");
        Files.writeString(outside, "ignored");
        Instant modifiedAt = Instant.parse("2026-09-18T00:00:00Z");
        Files.setLastModifiedTime(document, java.nio.file.attribute.FileTime.from(modifiedAt));

        List<StorageObjectMetadata> result;
        try (var objects = storageService.streamObjects("documents/", 100)) {
            result = objects.toList();
        }

        assertThat(result).containsExactly(new StorageObjectMetadata(
            new StoredFile(StorageProvider.LOCAL, BUCKET, "documents/a/source.txt"),
            6L,
            modifiedAt
        ));
    }

    @Test
    @DisplayName("존재하지 않는 파일을 읽으면 파일 없음 오류로 변환한다")
    void read_throwsNotFound_whenFileDoesNotExist() {
        StoredFile storedFile = new StoredFile(StorageProvider.LOCAL, BUCKET, "documents/missing.txt");

        assertStorageError(() -> storageService.read(storedFile), ErrorCode.FILE_OBJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("Root 밖으로 이동하는 Object Key는 저장하지 않는다")
    void store_rejectsPathTraversal() {
        byte[] content = "blocked".getBytes(StandardCharsets.UTF_8);
        Path outsidePath = temporaryRoot.getParent().resolve("outside.txt");

        assertStorageError(
            () -> storageService.store(
                new ByteArrayInputStream(content),
                content.length,
                "text/plain",
                "../outside.txt"
            ),
            ErrorCode.FILE_STORAGE_FAILED
        );
        assertThat(outsidePath).doesNotExist();
    }

    @Test
    @DisplayName("부모 경로의 Symbolic Link를 따라 Root 밖에 Directory를 만들지 않는다")
    void store_rejectsSymlinkParent_beforeCreatingOutsideDirectory() throws Exception {
        Files.createSymbolicLink(temporaryRoot.resolve("link"), outsideRoot);
        byte[] content = "blocked".getBytes(StandardCharsets.UTF_8);

        assertStorageError(
            () -> storageService.store(
                new ByteArrayInputStream(content),
                content.length,
                "text/plain",
                "link/new/source.txt"
            ),
            ErrorCode.FILE_STORAGE_FAILED
        );
        assertThat(outsideRoot.resolve("new")).doesNotExist();
    }

    @Test
    @DisplayName("현재 Adapter와 다른 Provider의 저장 위치는 설정 불일치로 거부한다")
    void read_rejectsDifferentProvider() throws Exception {
        Files.createDirectories(temporaryRoot.resolve("documents"));
        Files.writeString(temporaryRoot.resolve("documents/source.txt"), "source");
        StoredFile storedFile = new StoredFile(StorageProvider.MINIO, BUCKET, "documents/source.txt");

        assertStorageError(
            () -> storageService.read(storedFile),
            ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH
        );
    }

    @Test
    @DisplayName("현재 설정과 다른 Bucket의 저장 위치는 설정 불일치로 거부한다")
    void read_rejectsDifferentBucket() {
        StoredFile storedFile = new StoredFile(
            StorageProvider.LOCAL, "other-bucket", "documents/source.txt"
        );

        assertStorageError(
            () -> storageService.read(storedFile),
            ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH
        );
    }

    private void assertStorageError(ThrowingAction action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    /**
     * Checked Exception 여부와 무관하게 저장소 실패 동작을 전달하는 테스트용 함수다.
     */
    @FunctionalInterface
    private interface ThrowingAction {

        void run() throws Exception;
    }
}
