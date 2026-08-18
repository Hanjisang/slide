package com.medreport.slide.storage;

import com.medreport.common.BizException;
import io.minio.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class S3StorageProvider implements StorageProvider {
    @Override
    public void upload(StorageTarget target, String objectKey, InputStream input, long size, String contentType) {
        ensureS3(target);
        try {
            MinioClient client = client(target);
            ensureBucket(client, target.bucket());
            client.putObject(PutObjectArgs.builder().bucket(target.bucket()).object(key(target, objectKey))
                    .stream(input, size, -1).contentType(contentType == null ? "application/octet-stream" : contentType).build());
        } catch (Exception ex) {
            throw new BizException("对象上传失败: " + ex.getMessage());
        }
    }

    @Override
    public InputStream read(StorageTarget target, String objectKey) {
        ensureS3(target);
        try {
            return client(target).getObject(GetObjectArgs.builder().bucket(target.bucket()).object(key(target, objectKey)).build());
        } catch (Exception ex) {
            throw new BizException("对象读取失败: " + ex.getMessage());
        }
    }

    @Override
    public FileMetadata copy(StorageTarget source, String sourceObjectKey, StorageTarget target, String targetObjectKey) {
        FileMetadata sourceMetadata = metadata(source, sourceObjectKey);
        try (InputStream input = read(source, sourceObjectKey)) {
            upload(target, targetObjectKey, input, sourceMetadata.size(), "application/octet-stream");
        } catch (Exception ex) {
            throw ex instanceof BizException biz ? biz : new BizException("对象复制失败: " + ex.getMessage());
        }
        return metadata(target, targetObjectKey);
    }

    @Override
    public void delete(StorageTarget target, String objectKey) {
        try {
            client(target).removeObject(RemoveObjectArgs.builder().bucket(target.bucket()).object(key(target, objectKey)).build());
        } catch (Exception ex) {
            throw new BizException("对象删除失败: " + ex.getMessage());
        }
    }

    @Override
    public boolean exists(StorageTarget target, String objectKey) {
        try {
            client(target).statObject(StatObjectArgs.builder().bucket(target.bucket()).object(key(target, objectKey)).build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public FileMetadata metadata(StorageTarget target, String objectKey) {
        try {
            StatObjectResponse stat = client(target).statObject(StatObjectArgs.builder().bucket(target.bucket()).object(key(target, objectKey)).build());
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (DigestInputStream input = new DigestInputStream(read(target, objectKey), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return new FileMetadata(stat.size(), HexFormat.of().formatHex(digest.digest()));
        } catch (Exception ex) {
            throw ex instanceof BizException biz ? biz : new BizException("对象元数据读取失败: " + ex.getMessage());
        }
    }

    public void ensureBucket(StorageTarget target) {
        try { ensureBucket(client(target), target.bucket()); }
        catch (Exception ex) { throw new BizException("Bucket 初始化失败: " + ex.getMessage()); }
    }

    public Map<String,Object> usage(StorageTarget target){
        try{long bytes=0,objects=0;MinioClient client=client(target);ensureBucket(client,target.bucket());
            for(Result<io.minio.messages.Item> result:client.listObjects(ListObjectsArgs.builder().bucket(target.bucket()).recursive(true).build())){
                io.minio.messages.Item item=result.get();bytes+=item.size();objects++;}
            Map<String,Object> usage=new LinkedHashMap<>();usage.put("usedBytes",bytes);usage.put("objectCount",objects);
            usage.put("capacityBytes","UNKNOWN");usage.put("availableBytes","UNKNOWN");return usage;
        }catch(Exception ex){throw new BizException("存储统计失败: "+ex.getMessage());}
    }

    private void ensureBucket(MinioClient client, String bucket) throws Exception {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private MinioClient client(StorageTarget target) {
        return MinioClient.builder().endpoint(target.endpoint()).credentials(target.accessKey(), target.secretKey()).build();
    }

    private String key(StorageTarget target, String objectKey) {
        String base = target.basePath() == null ? "" : target.basePath().replaceAll("^/+|/+$", "");
        return base.isBlank() ? objectKey : base + "/" + objectKey;
    }

    private void ensureS3(StorageTarget target) {
        if (!"S3".equalsIgnoreCase(target.storageType())) throw new BizException("当前 StorageProvider 仅支持 S3");
        if (!target.enabled()) throw new BizException("存储目标已停用");
    }
}
