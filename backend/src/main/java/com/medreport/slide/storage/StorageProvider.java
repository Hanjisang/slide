package com.medreport.slide.storage;

import java.io.InputStream;

public interface StorageProvider {
    void upload(StorageTarget target, String objectKey, InputStream input, long size, String contentType);
    InputStream read(StorageTarget target, String objectKey);
    FileMetadata copy(StorageTarget source, String sourceObjectKey, StorageTarget target, String targetObjectKey);
    void delete(StorageTarget target, String objectKey);
    boolean exists(StorageTarget target, String objectKey);
    FileMetadata metadata(StorageTarget target, String objectKey);
}
