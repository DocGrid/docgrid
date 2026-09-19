package com.opensource.docgrid.domain.document.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.entity.FileObject;
import com.opensource.docgrid.domain.document.enums.StorageProvider;

/**
 * 외부 저장소 파일의 메타데이터를 보존하고 내용 Hash 기반 중복 업로드와 저장소 참조 검사를 조정한다.
 *
 * <p>동시 업로드는 PostgreSQL {@code ON CONFLICT DO NOTHING}으로 원자 수렴시키며, 호출 Service가
 * 삽입 결과에 따라 승자 행을 다시 조회하고 불필요한 외부 Object를 정리한다.
 */
public interface FileObjectRepository extends JpaRepository<FileObject, Long> {

    /** 같은 SHA-256과 파일 크기를 가진 재사용 가능한 FileObject를 조회한다. */
    Optional<FileObject> findByFileHashAndFileSize(String fileHash, Long fileSize);

    /** 저장소 목록 한 묶음에서 DB가 실제 참조하는 Object Key만 Projection으로 조회한다. */
    @Transactional(readOnly = true)
    @Query("""
        SELECT fileObject.objectKey
        FROM FileObject fileObject
        WHERE fileObject.storageProvider = :storageProvider
          AND fileObject.bucketName = :bucketName
          AND fileObject.objectKey IN :objectKeys
        """)
    List<String> findReferencedObjectKeys(
        @Param("storageProvider") StorageProvider storageProvider,
        @Param("bucketName") String bucketName,
        @Param("objectKeys") Collection<String> objectKeys
    );

    /**
     * Hash와 크기가 아직 없을 때만 FileObject 메타데이터를 원자 삽입한다.
     *
     * @return 새 행이 삽입되면 1, 동시 중복 행이 이미 있으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        INSERT INTO file_objects (
            bucket_name, object_key, original_filename, content_type,
            file_size, file_hash, storage_provider, uploaded_by, uploaded_at,
            created_at, updated_at
        ) VALUES (
            :bucketName, :objectKey, :originalFilename, :contentType,
            :fileSize, :fileHash, :storageProvider, :uploadedBy, CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
        )
        ON CONFLICT (file_hash, file_size) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
        @Param("bucketName") String bucketName,
        @Param("objectKey") String objectKey,
        @Param("originalFilename") String originalFilename,
        @Param("contentType") String contentType,
        @Param("fileSize") Long fileSize,
        @Param("fileHash") String fileHash,
        @Param("storageProvider") String storageProvider,
        @Param("uploadedBy") Long uploadedBy
    );
}
