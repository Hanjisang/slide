package com.medreport.slide;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medreport.common.BizException;
import com.medreport.system.AuditService;
import io.minio.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

@Service
public class SlideStorageService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SlideStorageService.class);
    private static final Set<String> ALLOWED = Set.of("svs","tmap","kfb","tron","mdsx","sdpc","dmetrix","fenlan","zyp","hwp","csp");
    private final MinioClient minio;
    private final JdbcTemplate jdbc;
    private final RestClient worker;
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public SlideStorageService(MinioClient minio, JdbcTemplate jdbc, RestClient.Builder builder, ObjectMapper objectMapper,
                               AuditService audit, @Value("${app.slide-worker-url}") String workerUrl) {
        this.minio = minio;
        this.jdbc = jdbc;
        this.worker = builder.baseUrl(workerUrl).build();
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureBucket("pathology-original");
            ensureBucket("pathology-cache");
        } catch (Exception ex) {
            log.warn("MinIO initialization deferred: {}", ex.getMessage());
        }
    }

    public long upload(long caseId, String slideNo, MultipartFile file) {
        if (file.isEmpty()) throw new BizException("请选择切片文件");
        String fileName = Optional.ofNullable(file.getOriginalFilename()).map(name -> name.replace('\\', '/')).map(name -> name.substring(name.lastIndexOf('/') + 1)).orElse("slide");
        String extension = extension(fileName);
        if (!ALLOWED.contains(extension)) throw new BizException("不支持的切片扩展名: " + extension);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM pathology_case WHERE id=?", Integer.class, caseId) == 0) throw new BizException("病理病例不存在");
        String pathologyNo = jdbc.queryForObject("SELECT pathology_no FROM pathology_case WHERE id=?", String.class, caseId);
        LocalDate now = LocalDate.now();
        String objectKey = "%04d/%02d/%02d/%s/%s".formatted(now.getYear(), now.getMonthValue(), now.getDayOfMonth(), pathologyNo, UUID.randomUUID() + "-" + fileName);
        try {
            String md5 = md5(file);
            jdbc.update("""
                    INSERT INTO slide_file(case_id,slide_no,file_name,file_extension,file_format,file_size,bucket_name,object_key,md5,status,sdk_status)
                    VALUES (?,?,?,?,?,?,?,?,?,'UPLOADING','UNKNOWN')
                    """, caseId, slideNo, fileName, extension, extension.toUpperCase(Locale.ROOT), file.getSize(), "pathology-original", objectKey, md5);
            long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            try (InputStream input = file.getInputStream()) {
                minio.putObject(PutObjectArgs.builder().bucket("pathology-original").object(objectKey).stream(input, file.getSize(), -1)
                        .contentType("application/octet-stream").build());
            }
            jdbc.update("UPDATE slide_file SET status='UPLOADED' WHERE id=?", id);
            audit.log("SYSTEM", "上传切片", "数字切片", id, "SUCCESS", fileName);
            analyze(id);
            return id;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("切片上传失败: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(long id) {
        Map<String, Object> slide = find(id);
        jdbc.update("UPDATE slide_file SET status='PARSING',error_message=NULL WHERE id=?", id);
        try {
            Map<String, Object> result = worker.post().uri("/api/slides/{id}/analyze", id).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("bucket", slide.get("bucket_name"), "objectKey", slide.get("object_key"), "fileName", slide.get("file_name")))
                    .retrieve().body(Map.class);
            if (result == null) throw new BizException("Slide Worker 未返回解析结果");
            String status = String.valueOf(result.get("status"));
            boolean ready = "READY".equals(status);
            jdbc.update("""
                    UPDATE slide_file SET file_format=?,adapter_type=?,sdk_status=?,width=?,height=?,level_count=?,levels_json=?,status=?,error_message=? WHERE id=?
                    """, result.getOrDefault("format", slide.get("file_format")), result.get("adapterType"), result.get("sdkStatus"),
                    result.get("width"), result.get("height"), result.get("levelCount"),
                    result.get("levels") == null ? null : objectMapper.writeValueAsString(result.get("levels")), ready ? "READY" : "FAILED",
                    ready ? null : result.getOrDefault("error", status), id);
            return result;
        } catch (Exception ex) {
            jdbc.update("UPDATE slide_file SET status='FAILED',error_message=? WHERE id=?", ex.getMessage(), id);
            audit.log("SYSTEM", "解析切片", "数字切片", id, "FAILED", ex.getMessage());
            throw ex instanceof BizException biz ? biz : new BizException("切片解析失败: " + ex.getMessage());
        }
    }

    public byte[] tile(long id, int level, int x, int y) {
        findReady(id);
        try {
            return worker.get().uri(uri -> uri.path("/api/slides/{id}/tiles/{level}/{x}/{y}").queryParam("tile_size", 256).build(id, level, x, y))
                    .retrieve().body(byte[].class);
        } catch (Exception ex) { throw new BizException("Tile 读取失败: " + ex.getMessage()); }
    }

    public byte[] thumbnail(long id) {
        findReady(id);
        try { return worker.get().uri("/api/slides/{id}/thumbnail", id).retrieve().body(byte[].class); }
        catch (Exception ex) { throw new BizException("缩略图读取失败: " + ex.getMessage()); }
    }

    public Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM slide_file WHERE id=?", id);
        if (rows.isEmpty()) throw new BizException("切片不存在");
        return rows.getFirst();
    }

    private Map<String, Object> findReady(long id) {
        Map<String, Object> slide = find(id);
        if (!"READY".equals(slide.get("status"))) throw new BizException("切片尚未就绪");
        return slide;
    }

    private void ensureBucket(String name) throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(name).build())) minio.makeBucket(MakeBucketArgs.builder().bucket(name).build());
    }

    private String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String md5(MultipartFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (DigestInputStream input = new DigestInputStream(file.getInputStream(), digest)) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(digest.digest());
    }
}

