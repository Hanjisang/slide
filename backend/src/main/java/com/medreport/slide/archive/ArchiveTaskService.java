package com.medreport.slide.archive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ArchiveTaskService {
    private final JdbcTemplate jdbc;

    public ArchiveTaskService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long create(long slideId, long sourceId, long targetId, String sourceKey, String targetKey,
                       String sourceMd5, String operator, String note) {
        jdbc.update("""
                INSERT INTO archive_task(slide_id,source_storage_id,target_storage_id,source_object_key,target_object_key,
                  status,progress,source_md5,operator,note) VALUES (?,?,?,?,?,'PENDING',0,?,?,?)
                """, slideId, sourceId, targetId, sourceKey, targetKey, sourceMd5, operator, note);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void progress(long id, String status, int progress) {
        jdbc.update("UPDATE archive_task SET status=?,progress=?,started_at=COALESCE(started_at,NOW()) WHERE id=?", status, progress, id);
    }

    public void success(long id, String targetMd5) {
        jdbc.update("UPDATE archive_task SET status='SUCCESS',progress=100,target_md5=?,finished_at=NOW(),error_message=NULL WHERE id=?", targetMd5, id);
    }

    public void failed(long id, String message) {
        jdbc.update("UPDATE archive_task SET status='FAILED',error_message=?,finished_at=NOW() WHERE id=?", message, id);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT a.*,s.slide_no,s.display_name,t.name target_name FROM archive_task a
                JOIN slide_file s ON s.id=a.slide_id JOIN storage_target t ON t.id=a.target_storage_id
                ORDER BY a.id DESC LIMIT 500
                """);
    }
}
