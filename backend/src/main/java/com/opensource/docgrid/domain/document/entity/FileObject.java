package com.opensource.docgrid.domain.document.entity;

import java.time.LocalDateTime;

import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.global.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 파일 오브젝트(실제 바이너리 위치) 테이블.
 *
 * <p>역할: 업로드된 파일 바이너리의 저장 위치와 해시를 기록한다.
 * 이유: 실제 파일 바이너리는 DB에 저장하지 않고 Local/MinIO/S3/GCS 등 외부 파일 저장소에 저장하므로,
 * 그 논리 위치(bucket/objectKey)와 무결성 검증용 해시만 이 테이블에 보관한다.
 * 관계: document_versions.file_object_id가 이 테이블을 참조한다(하나의 파일이 여러 버전에서 재사용될 수 있음).
 * unique 제약: 동일 storage_provider/bucket/objectKey 조합과 file_hash/file_size 조합은 각각 유일해야 한다.
 * index: uploaded_by에 대한 조회 인덱스를 둔다.
 *
 * <p>주의사항: file_hash+file_size가 동일한 경우 동일 파일로 간주하여 기존 FileObject를 재사용한다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "file_objects",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_file_objects_storage_provider_bucket_name_object_key",
                        columnNames = {"storage_provider", "bucket_name", "object_key"}
                ),
                @UniqueConstraint(
                        name = "uk_file_objects_file_hash_file_size",
                        columnNames = {"file_hash", "file_size"}
                )
        },
        indexes = {
                @Index(name = "idx_file_objects_uploaded_by", columnList = "uploaded_by")
        }
)
public class FileObject extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bucket_name", nullable = false, length = 255)
    private String bucketName;

    @Column(name = "object_key", nullable = false, length = 1000)
    private String objectKey;

    @Column(name = "original_filename", nullable = false, length = 500)
    private String originalFilename;

    @Column(name = "content_type", length = 200)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "file_hash", nullable = false, length = 128)
    private String fileHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_provider", nullable = false, length = 20)
    private StorageProvider storageProvider;

    // 파일을 업로드한 사용자
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Builder
    public FileObject(String bucketName, String objectKey, String originalFilename, String contentType,
                       Long fileSize, String fileHash, StorageProvider storageProvider, User uploadedBy,
                       LocalDateTime uploadedAt) {
        this.bucketName = bucketName;
        this.objectKey = objectKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.fileHash = fileHash;
        this.storageProvider = storageProvider;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }
}
