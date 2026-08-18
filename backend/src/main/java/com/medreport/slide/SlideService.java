package com.medreport.slide;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medreport.common.BizException;
import com.medreport.file.FileAssetService;
import com.medreport.slide.file.SlideFileService;
import com.medreport.slide.storage.S3StorageProvider;
import com.medreport.slide.storage.StorageTarget;
import com.medreport.slide.storage.StorageTargetService;
import com.medreport.slide.worker.SlideWorkerClient;
import com.medreport.system.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

@Service
@Order(10)
public class SlideService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SlideService.class);
    private static final Set<String> ALLOWED = Set.of("svs","tmap","kfb","tron","mdsx","sdpc","dmetrix","fenlan","zyp","hwp","csp");
    private final JdbcTemplate jdbc;
    private final StorageTargetService targets;
    private final S3StorageProvider storage;
    private final SlideFileService files;
    private final SlideWorkerClient worker;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final FileAssetService fileAssets;

    public SlideService(JdbcTemplate jdbc, StorageTargetService targets, S3StorageProvider storage, SlideFileService files,
                        SlideWorkerClient worker, ObjectMapper objectMapper, AuditService audit, FileAssetService fileAssets) {
        this.jdbc = jdbc; this.targets = targets; this.storage = storage; this.files = files;
        this.worker = worker; this.objectMapper = objectMapper; this.audit = audit;
        this.fileAssets = fileAssets;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (Map<String, Object> row : targets.listSafe()) if (Boolean.TRUE.equals(asBoolean(row.get("enabled")))) {
                storage.ensureBucket(targets.find(((Number) row.get("id")).longValue()));
            }
        } catch (Exception ex) { log.warn("Storage target initialization deferred: {}", ex.getMessage()); }
    }

    public long upload(long caseId, String slideNo, MultipartFile file) {
        if (file.isEmpty()) throw new BizException("请选择切片文件");
        String fileName = safeName(file.getOriginalFilename());
        String extension = extension(fileName);
        if (!ALLOWED.contains(extension)) throw new BizException("不支持的切片扩展名: " + extension);
        List<Map<String, Object>> cases = jdbc.queryForList("SELECT * FROM pathology_case WHERE id=?", caseId);
        if (cases.isEmpty()) throw new BizException("病理病例不存在");
        Map<String, Object> pathologyCase = cases.getFirst();
        StorageTarget target = targets.requireClass("HOT");
        LocalDate now = LocalDate.now();
        String objectKey = "%04d/%02d/%02d/%s/%s".formatted(now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                pathologyCase.get("pathology_no"), UUID.randomUUID() + "-" + fileName);
        try {
            String md5 = md5(file);
            jdbc.update("""
                    INSERT INTO slide_file(case_id,slide_no,file_name,display_name,specimen_type_code,file_extension,file_format,file_size,
                      bucket_name,object_key,md5,storage_target_id,storage_class,status,sdk_status,scan_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'UPLOADING','UNKNOWN',NOW())
                    """, caseId, slideNo, fileName, fileName, pathologyCase.get("specimen_type_code"), extension,
                    extension.toUpperCase(Locale.ROOT), file.getSize(), target.bucket(), objectKey, md5, target.id(), target.storageClass());
            long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            try (InputStream input = file.getInputStream()) {
                storage.upload(target, objectKey, input, file.getSize(), "application/octet-stream");
            }
            jdbc.update("UPDATE slide_file SET status='UPLOADED' WHERE id=?", id);
            fileAssets.registerExisting("SLIDE", "PATHOLOGY", id, fileName, fileName, target.id(), objectKey, file.getSize(), md5, "SYSTEM");
            audit.log("SYSTEM", "上传切片", "数字切片", id, "SUCCESS", fileName);
            analyze(id);
            return id;
        } catch (Exception ex) {
            throw ex instanceof BizException biz ? biz : new BizException("切片上传失败: " + ex.getMessage());
        }
    }

    public Map<String, Object> analyze(long id) {
        Map<String, Object> slide = files.find(id);
        jdbc.update("UPDATE slide_file SET status='PARSING',error_message=NULL WHERE id=?", id);
        try {
            Map<String, Object> result = worker.analyze(id, String.valueOf(slide.get("bucket_name")),
                    String.valueOf(slide.get("object_key")), String.valueOf(slide.get("file_name")));
            String resultStatus = String.valueOf(result.get("status"));
            boolean ready = "READY".equals(resultStatus);
            jdbc.update("""
                    UPDATE slide_file SET file_format=?,adapter_type=?,sdk_status=?,width=?,height=?,level_count=?,levels_json=?,status=?,error_message=? WHERE id=?
                    """, result.getOrDefault("format", slide.get("file_format")), result.get("adapterType"), result.get("sdkStatus"),
                    result.get("width"), result.get("height"), result.get("levelCount"),
                    result.get("levels") == null ? null : objectMapper.writeValueAsString(result.get("levels")), ready ? "READY" : "FAILED",
                    ready ? null : result.getOrDefault("error", resultStatus), id);
            return result;
        } catch (Exception ex) {
            jdbc.update("UPDATE slide_file SET status='FAILED',error_message=? WHERE id=?", ex.getMessage(), id);
            audit.log("SYSTEM", "解析切片", "数字切片", id, "FAILED", ex.getMessage());
            throw ex instanceof BizException biz ? biz : new BizException("切片解析失败: " + ex.getMessage());
        }
    }

    public byte[] tile(long id, int level, int x, int y) { requireReadable(id); return worker.tile(id, level, x, y); }
    public byte[] thumbnail(long id) { requireReadable(id); return worker.thumbnail(id); }

    private void requireReadable(long id) {
        String status = String.valueOf(files.find(id).get("status"));
        if (!Set.of("READY", "ARCHIVED").contains(status)) throw new BizException("切片尚未就绪");
    }

    private String safeName(String original) {
        String value = original == null ? "slide" : original.replace('\\', '/');
        return value.substring(value.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
    }
    private String extension(String name) { int index=name.lastIndexOf('.'); return index<0?"":name.substring(index+1).toLowerCase(Locale.ROOT); }
    private String md5(MultipartFile file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("MD5");
        try(DigestInputStream input=new DigestInputStream(file.getInputStream(),digest)){input.transferTo(java.io.OutputStream.nullOutputStream());}
        return HexFormat.of().formatHex(digest.digest());
    }
    private boolean asBoolean(Object value) { return value instanceof Boolean b ? b : value instanceof Number n && n.intValue()!=0; }
}
