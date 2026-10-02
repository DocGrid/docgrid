package com.opensource.docgrid.domain.document.config;

/**
 * 현재 실행 환경에서 활성화할 수 있는 파일 저장소 Adapter 종류를 제한한다.
 * DB에 기록되는 StorageProvider와 달리, 실제로 등록된 Adapter만 설정 값으로 노출한다.
 */
public enum FileStorageType {
    LOCAL,
    MINIO,
    S3,
    GCS
}
