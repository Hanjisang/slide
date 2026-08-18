package com.medreport.slide.storage;

public record StorageTarget(long id, String name, String storageType, String endpoint, String accessKey,
                            String secretKey, String bucket, String basePath, String storageClass, boolean enabled) {}
