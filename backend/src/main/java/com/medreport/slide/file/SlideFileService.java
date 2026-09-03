package com.medreport.slide.file;

import com.medreport.common.BizException;
import com.medreport.slide.storage.FileMetadata;
import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTarget;
import com.medreport.slide.storage.StorageTargetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SlideFileService {
    public record DownloadFile(String fileName, long size, InputStream input) {}
    private final JdbcTemplate jdbc;
    private final StorageTargetService targets;
    private final StorageProvider storage;

    public SlideFileService(JdbcTemplate jdbc, StorageTargetService targets, StorageProvider storage) {
        this.jdbc = jdbc;
        this.targets = targets;
        this.storage = storage;
    }

    public List<Map<String, Object>> list(Map<String, String> filters) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.*,c.pathology_no,c.specimen_name,p.name patient_name,t.name storage_target_name,t.bucket storage_bucket
                FROM slide_file s JOIN pathology_case c ON c.id=s.case_id LEFT JOIN patient p ON p.id=c.patient_id
                LEFT JOIN storage_target t ON t.id=s.storage_target_id WHERE s.deleted=0
                """);
        List<Object> args = new ArrayList<>();
        contains(sql, args, "c.pathology_no", filters.get("pathologyNo"));
        contains(sql, args, "p.name", filters.get("patientName"));
        contains(sql, args, "s.slide_no", filters.get("slideNo"));
        contains(sql, args, "COALESCE(s.display_name,s.file_name)", filters.get("fileName"));
        equals(sql, args, "s.specimen_type_code", filters.get("specimenType"));
        equals(sql, args, "s.file_format", filters.get("format"));
        equals(sql, args, "s.archive_status", filters.get("archiveStatus"));
        equals(sql, args, "s.storage_class", filters.get("storageClass"));
        sql.append(" ORDER BY s.id DESC LIMIT 1000");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public List<Map<String, Object>> cases() {
        return jdbc.queryForList("SELECT c.*,p.name patient_name FROM pathology_case c LEFT JOIN patient p ON p.id=c.patient_id ORDER BY c.id DESC LIMIT 1000");
    }

    public Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM slide_file WHERE id=? AND deleted=0", id);
        if (rows.isEmpty()) throw new BizException("切片不存在或已删除");
        return rows.getFirst();
    }

    public void rename(long id, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) throw new BizException("展示名称不能为空");
        int updated = jdbc.update("UPDATE slide_file SET display_name=?,version_no=version_no+1 WHERE id=? AND deleted=0", displayName.trim(), id);
        if (updated == 0) throw new BizException("切片不存在或已删除");
    }

    public void softDelete(long id, String username) {
        int updated = jdbc.update("UPDATE slide_file SET deleted=1,deleted_at=NOW(),deleted_by=? WHERE id=? AND deleted=0", username, id);
        if (updated == 0) throw new BizException("切片不存在或已删除");
    }

    public DownloadFile download(long id) {
        Map<String, Object> slide = find(id);
        if ("METADATA_ONLY".equals(String.valueOf(slide.get("status")))) {
            throw new BizException("该切片仅登记元数据，未提供原始文件，无法下载");
        }
        StorageTarget target = slide.get("storage_target_id") == null
                ? targets.requireClass("HOT") : targets.find(((Number) slide.get("storage_target_id")).longValue());
        String objectKey = String.valueOf(slide.get("object_key"));
        FileMetadata metadata = storage.metadata(target, objectKey);
        String name = slide.get("display_name") == null ? String.valueOf(slide.get("file_name")) : String.valueOf(slide.get("display_name"));
        if (!name.contains(".")) name += "." + slide.get("file_extension");
        return new DownloadFile(name, metadata.size(), storage.read(target, objectKey));
    }

    private void contains(StringBuilder sql, List<Object> args, String field, String value) {
        if (value != null && !value.isBlank()) { sql.append(" AND ").append(field).append(" LIKE ?"); args.add("%" + value.trim() + "%"); }
    }

    private void equals(StringBuilder sql, List<Object> args, String field, String value) {
        if (value != null && !value.isBlank()) { sql.append(" AND ").append(field).append("=?"); args.add(value.trim()); }
    }
}
