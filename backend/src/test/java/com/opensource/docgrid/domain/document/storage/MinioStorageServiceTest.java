package com.opensource.docgrid.domain.document.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.opensource.docgrid.domain.document.config.FileStorageProperties;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.ListObjectsArgs;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import okhttp3.Headers;

/**
 * MinIO Object 전체 읽기의 위치 선택, Stream 종료와 오류 변환 계약을 검증한다.
 *
 * <p>FileObject Snapshot의 Bucket과 Object Key를 사용하고 Object 없음과 일반 저장소 장애를
 * 서로 다른 외부 오류로 구분하는지 확인한다.
 */
@DisplayName("MinioStorageService 테스트")
class MinioStorageServiceTest {

    private static final StoredFile STORED_FILE = new StoredFile(
        StorageProvider.MINIO, "source-bucket", "documents/source.txt"
    );

    private MinioClient minioClient;
    private MinioStorageService storageService;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        FileStorageProperties fileStorageProperties = new FileStorageProperties();
        fileStorageProperties.setBucket(STORED_FILE.bucketName());
        storageService = new MinioStorageService(
            minioClient,
            fileStorageProperties
        );
    }

    @Test
    @DisplayName("공통 Bucket에 저장하고 MinIO Provider 위치를 반환한다")
    void store_returnsMinioLocation() throws Exception {
        byte[] content = "DocGrid MinIO 원본".getBytes(StandardCharsets.UTF_8);
        given(minioClient.bucketExists(any())).willReturn(true);
        ArgumentCaptor<PutObjectArgs> argsCaptor = ArgumentCaptor.forClass(PutObjectArgs.class);

        StoredFile result = storageService.store(
            new ByteArrayInputStream(content),
            content.length,
            "text/plain",
            "documents/new.txt"
        );

        then(minioClient).should().putObject(argsCaptor.capture());
        assertThat(result).isEqualTo(
            new StoredFile(StorageProvider.MINIO, STORED_FILE.bucketName(), "documents/new.txt")
        );
        assertThat(argsCaptor.getValue().bucket()).isEqualTo(STORED_FILE.bucketName());
        assertThat(argsCaptor.getValue().object()).isEqualTo("documents/new.txt");
    }

    @Test
    @DisplayName("전달된 Bucket과 Object Key로 모든 Byte를 읽고 Stream을 닫는다")
    void read_returnsAllBytesAndClosesStream() throws Exception {
        byte[] content = "DocGrid 원본".getBytes(StandardCharsets.UTF_8);
        CloseTrackingInputStream source = new CloseTrackingInputStream(content);
        GetObjectResponse response = new GetObjectResponse(
            new Headers.Builder().build(),
            STORED_FILE.bucketName(),
            null,
            STORED_FILE.objectKey(),
            source
        );
        ArgumentCaptor<GetObjectArgs> argsCaptor = ArgumentCaptor.forClass(GetObjectArgs.class);
        given(minioClient.getObject(argsCaptor.capture())).willReturn(response);

        byte[] result = storageService.read(STORED_FILE);

        assertThat(result).isEqualTo(content);
        assertThat(source.closed).isTrue();
        assertThat(argsCaptor.getValue().bucket()).isEqualTo(STORED_FILE.bucketName());
        assertThat(argsCaptor.getValue().object()).isEqualTo(STORED_FILE.objectKey());
    }

    @Test
    @DisplayName("접두사와 Page 크기로 MinIO Object Metadata를 조회한다")
    void streamObjects_listsMetadataWithConfiguredScope() throws Exception {
        Instant modifiedAt = Instant.parse("2026-09-18T00:00:00Z");
        Item item = mock(Item.class);
        given(item.objectName()).willReturn("documents/a/source.pdf");
        given(item.size()).willReturn(37L);
        given(item.lastModified()).willReturn(modifiedAt.atZone(ZoneOffset.UTC));
        ArgumentCaptor<ListObjectsArgs> argsCaptor = ArgumentCaptor.forClass(ListObjectsArgs.class);
        given(minioClient.listObjects(argsCaptor.capture()))
            .willReturn(List.of(new Result<>(item)));

        List<StorageObjectMetadata> result;
        try (var objects = storageService.streamObjects("documents/", 200)) {
            result = objects.toList();
        }

        assertThat(result).containsExactly(new StorageObjectMetadata(
            new StoredFile(StorageProvider.MINIO, STORED_FILE.bucketName(), "documents/a/source.pdf"),
            37L,
            modifiedAt
        ));
        assertThat(argsCaptor.getValue().bucket()).isEqualTo(STORED_FILE.bucketName());
        assertThat(argsCaptor.getValue().prefix()).isEqualTo("documents/");
        assertThat(argsCaptor.getValue().maxKeys()).isEqualTo(200);
        assertThat(argsCaptor.getValue().recursive()).isTrue();
    }

    @Test
    @DisplayName("MinIO Object가 없으면 파일 없음 오류로 변환한다")
    void read_throwsNotFound_when_objectDoesNotExist() throws Exception {
        ErrorResponse errorResponse = new ErrorResponse(
            "NoSuchKey",
            "Object does not exist",
            STORED_FILE.bucketName(),
            STORED_FILE.objectKey(),
            null,
            "request-id",
            "host-id"
        );
        given(minioClient.getObject(any(GetObjectArgs.class)))
            .willThrow(new ErrorResponseException(errorResponse, null, "request"));

        assertReadError(ErrorCode.FILE_OBJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("MinIO 일반 읽기 장애는 저장소 사용 불가 오류로 변환한다")
    void read_throwsStorageFailure_when_sdkFails() throws Exception {
        given(minioClient.getObject(any(GetObjectArgs.class)))
            .willThrow(new IOException("connection closed"));

        assertReadError(ErrorCode.FILE_STORAGE_FAILED);
    }

    @Test
    @DisplayName("현재 Adapter와 다른 Provider의 저장 위치는 설정 불일치로 거부한다")
    void read_rejectsDifferentProvider() {
        StoredFile localFile = new StoredFile(
            StorageProvider.LOCAL, STORED_FILE.bucketName(), STORED_FILE.objectKey()
        );

        assertThatThrownBy(() -> storageService.read(localFile))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH));
    }

    @Test
    @DisplayName("현재 설정과 다른 Bucket의 저장 위치는 설정 불일치로 거부한다")
    void read_rejectsDifferentBucket() {
        StoredFile otherBucketFile = new StoredFile(
            StorageProvider.MINIO, "other-bucket", STORED_FILE.objectKey()
        );

        assertThatThrownBy(() -> storageService.read(otherBucketFile))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.FILE_STORAGE_CONFIGURATION_MISMATCH));
    }

    private void assertReadError(ErrorCode errorCode) {
        assertThatThrownBy(() -> storageService.read(STORED_FILE))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    /**
     * MinIO 응답이 닫힐 때 원본 Stream까지 닫히는지 관찰하는 테스트용 Stream.
     */
    private static class CloseTrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        CloseTrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
