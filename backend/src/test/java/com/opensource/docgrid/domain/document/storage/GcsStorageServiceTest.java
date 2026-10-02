package com.opensource.docgrid.domain.document.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.config.FileStorageType;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * GCS Adapter의 저장 위치, 스트리밍 업로드와 오류 변환 계약을 검증한다.
 * 실제 Bucket이나 Google Cloud 인증에는 접근하지 않는다.
 */
@DisplayName("GcsStorageService 테스트")
class GcsStorageServiceTest {

    private static final StoredFile STORED_FILE = new StoredFile(
        StorageProvider.GCS, "test-bucket", "documents/source.pdf"
    );

    private Storage storage;
    private GcsStorageService storageService;

    @BeforeEach
    void setUp() {
        storage = mock(Storage.class);
        FileStorageProperties properties = new FileStorageProperties();
        properties.setType(FileStorageType.GCS);
        properties.setBucket(STORED_FILE.bucketName());
        storageService = new GcsStorageService(storage, properties);
    }

    @Test
    @DisplayName("Bucket이 없으면 Adapter 생성 단계에서 거부한다")
    void constructor_rejectsMissingBucket() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setType(FileStorageType.GCS);

        assertThatThrownBy(() -> new GcsStorageService(storage, properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("STORAGE_BUCKET");
    }

    @Test
    @DisplayName("원본을 스트리밍 저장하고 GCS 위치를 반환한다")
    void store_returnsGcsLocation() throws Exception {
        byte[] content = "DocGrid PDF test".getBytes(StandardCharsets.UTF_8);
        WriteChannel channel = mock(WriteChannel.class);
        given(channel.write(any(ByteBuffer.class))).willAnswer(invocation -> {
            ByteBuffer buffer = invocation.getArgument(0);
            int length = buffer.remaining();
            buffer.position(buffer.limit());
            return length;
        });
        ArgumentCaptor<BlobInfo> blobCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        given(storage.writer(blobCaptor.capture(), any(Storage.BlobWriteOption.class)))
            .willReturn(channel);

        StoredFile result = storageService.store(
            new ByteArrayInputStream(content), content.length, "application/pdf", "documents/new.pdf"
        );

        assertThat(result).isEqualTo(new StoredFile(
            StorageProvider.GCS, STORED_FILE.bucketName(), "documents/new.pdf"
        ));
        assertThat(blobCaptor.getValue().getBlobId())
            .isEqualTo(BlobId.of(STORED_FILE.bucketName(), "documents/new.pdf"));
        assertThat(blobCaptor.getValue().getContentType()).isEqualTo("application/pdf");
        then(channel).should().close();
    }

    @Test
    @DisplayName("저장된 Object의 전체 Byte를 읽는다")
    void read_returnsAllBytes() {
        byte[] content = "PDF bytes".getBytes(StandardCharsets.UTF_8);
        given(storage.readAllBytes(BlobId.of(STORED_FILE.bucketName(), STORED_FILE.objectKey())))
            .willReturn(content);

        assertThat(storageService.read(STORED_FILE)).isEqualTo(content);
    }

    @Test
    @DisplayName("GCS Object가 없으면 파일 없음 오류로 변환한다")
    void read_throwsNotFound_when_objectDoesNotExist() {
        given(storage.readAllBytes(any(BlobId.class))).willThrow(new StorageException(404, "missing"));

        assertThatThrownBy(() -> storageService.read(STORED_FILE))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FILE_OBJECT_NOT_FOUND));
    }

    @Test
    @DisplayName("GCS 조회 장애는 저장소 오류로 변환한다")
    void read_throwsStorageFailure_when_serviceFails() {
        given(storage.readAllBytes(any(BlobId.class))).willThrow(new StorageException(503, "unavailable"));

        assertThatThrownBy(() -> storageService.read(STORED_FILE))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FILE_STORAGE_FAILED));
    }

    @Test
    @DisplayName("현재 Bucket의 Object를 삭제한다")
    void delete_removesStoredObject() {
        storageService.delete(STORED_FILE);

        then(storage).should().delete(BlobId.of(STORED_FILE.bucketName(), STORED_FILE.objectKey()));
    }

    @Test
    @DisplayName("다른 Provider나 Bucket은 읽기 전에 거부한다")
    void read_rejectsDifferentLocation() {
        StoredFile wrongProvider = new StoredFile(
            StorageProvider.S3, STORED_FILE.bucketName(), STORED_FILE.objectKey()
        );
        StoredFile wrongBucket = new StoredFile(
            StorageProvider.GCS, "other-bucket", STORED_FILE.objectKey()
        );

        for (StoredFile location : new StoredFile[] {wrongProvider, wrongBucket}) {
            assertThatThrownBy(() -> storageService.read(location))
                .isInstanceOfSatisfying(DocGridException.class,
                    exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH));
        }
        then(storage).shouldHaveNoInteractions();
    }
}
