package com.opensource.docgrid.domain.document.enums;

/**
 * 파일 바이너리가 저장되는 스토리지 종류.
 */
public enum StorageProvider {
    MINIO,
    S3,
    LOCAL,
    GCS
}
