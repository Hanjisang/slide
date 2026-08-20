package com.medreport.slide.archive;

import com.medreport.common.BizException;
import com.medreport.slide.file.SlideFileService;
import com.medreport.slide.storage.FileMetadata;
import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTarget;
import com.medreport.slide.storage.StorageTargetService;
import com.medreport.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ArchiveService {
    private final JdbcTemplate jdbc;
    private final SlideFileService files;
    private final StorageTargetService targets;
    private final StorageProvider storage;
    private final ArchiveTaskService tasks;
    private final AuditService audit;

    public ArchiveService(JdbcTemplate jdbc, SlideFileService files, StorageTargetService targets,
                          StorageProvider storage, ArchiveTaskService tasks, AuditService audit) {
        this.jdbc=jdbc; this.files=files; this.targets=targets; this.storage=storage; this.tasks=tasks; this.audit=audit;
    }

    public Map<String, Object> archive(long slideId, Long targetId, String note, String operator) {
        Map<String, Object> slide = files.find(slideId);
        if (!"READY".equals(slide.get("status"))) throw new BizException("只有 READY 切片可以归档");
        StorageTarget source = slide.get("storage_target_id") == null ? targets.requireClass("HOT")
                : targets.find(((Number) slide.get("storage_target_id")).longValue());
        StorageTarget target = targetId == null ? targets.requireClass("ARCHIVE") : targets.find(targetId);
        if (!"ARCHIVE".equals(target.storageClass())) throw new BizException("目标必须是 ARCHIVE 存储");
        String sourceKey = String.valueOf(slide.get("object_key"));
        String targetKey = "archive/%s/%s".formatted(LocalDate.now(), sourceKey);
        String expectedMd5 = slide.get("md5") == null ? null : String.valueOf(slide.get("md5"));
        long taskId = tasks.create(slideId, source.id(), target.id(), sourceKey, targetKey, expectedMd5, operator, note);
        try {
            tasks.progress(taskId, "COPYING", 10);
            FileMetadata sourceMetadata = storage.metadata(source, sourceKey);
            FileMetadata targetMetadata = storage.copy(source, sourceKey, target, targetKey);
            tasks.progress(taskId, "VERIFYING", 80);
            if (!storage.exists(target, targetKey)) throw new BizException("归档目标对象不存在");
            if (sourceMetadata.size() != targetMetadata.size()) throw new BizException("归档文件大小校验失败");
            if (!Objects.equals(sourceMetadata.md5(), targetMetadata.md5())) throw new BizException("归档 MD5 校验失败");
            if (expectedMd5 != null && !expectedMd5.equalsIgnoreCase(targetMetadata.md5())) throw new BizException("归档 MD5 与数据库记录不一致");
            jdbc.update("""
                    UPDATE slide_file SET archive_status='SUCCESS',archive_target_id=?,archive_object_key=?,archived_at=NOW(),
                      archived_by=?,status='ARCHIVED' WHERE id=?
                    """, target.id(), targetKey, operator, slideId);
            tasks.success(taskId, targetMetadata.md5());
            audit.log(operator, "归档切片", "数字切片", slideId, "SUCCESS", target.name());
            return jdbc.queryForMap("SELECT * FROM archive_task WHERE id=?", taskId);
        } catch (Exception ex) {
            tasks.failed(taskId, ex.getMessage());
            jdbc.update("UPDATE slide_file SET archive_status='FAILED' WHERE id=?", slideId);
            audit.log(operator, "归档切片", "数字切片", slideId, "FAILED", ex.getMessage());
            throw ex instanceof BizException biz ? biz : new BizException("归档失败: " + ex.getMessage());
        }
    }

    public Map<String, Object> archive(long slideId, Long targetId, String note, HttpServletRequest request) {
        return archive(slideId, targetId, note, AuditService.username(request));
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void runPolicies() {
        List<Map<String, Object>> policies = jdbc.queryForList("SELECT * FROM archive_policy WHERE enabled=1 AND condition_type='UPLOAD_AGE_DAYS'");
        for (Map<String, Object> policy : policies) {
            int days;
            try { days = Integer.parseInt(String.valueOf(policy.get("condition_value"))); }
            catch (Exception ignored) { continue; }
            List<Map<String, Object>> slides = jdbc.queryForList("""
                    SELECT id FROM slide_file WHERE deleted=0 AND status='READY' AND archive_status='NOT_ARCHIVED'
                      AND created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT 50
                    """, days);
            for (Map<String, Object> slide : slides) {
                try { archive(((Number) slide.get("id")).longValue(), ((Number) policy.get("target_storage_id")).longValue(),
                        "自动归档策略: " + policy.get("name"), "SYSTEM"); } catch (Exception ignored) { }
            }
            jdbc.update("UPDATE archive_policy SET last_run_at=NOW() WHERE id=?", policy.get("id"));
        }
    }
}
