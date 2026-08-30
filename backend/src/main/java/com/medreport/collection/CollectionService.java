package com.medreport.collection;

import com.medreport.common.BizException;
import com.medreport.datasource.DataSourceController;
import com.medreport.governance.MappingEngine;
import com.medreport.governance.ValidationService;
import com.medreport.medical.MedicalDataService;
import com.medreport.security.SecretCipher;
import com.medreport.system.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class CollectionService {
    private static final Pattern FORBIDDEN_SQL = Pattern.compile("(?is)(;|--|/\\*|\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|GRANT|CALL)\\b)");
    private final JdbcTemplate jdbc;
    private final DataSourceController dataSources;
    private final SecretCipher cipher;
    private final MappingEngine mappingEngine;
    private final MedicalDataService medicalData;
    private final ValidationService validation;
    private final AuditService audit;

    public CollectionService(JdbcTemplate jdbc, DataSourceController dataSources, SecretCipher cipher, MappingEngine mappingEngine,
                             MedicalDataService medicalData, ValidationService validation, AuditService audit) {
        this.jdbc = jdbc;
        this.dataSources = dataSources;
        this.cipher = cipher;
        this.mappingEngine = mappingEngine;
        this.medicalData = medicalData;
        this.validation = validation;
        this.audit = audit;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
    public void runDueTasks() {
        List<Map<String, Object>> due = jdbc.queryForList("SELECT id FROM collect_task WHERE enabled=1 AND (next_run_time IS NULL OR next_run_time<=NOW())");
        for (Map<String, Object> task : due) {
            try { execute(((Number) task.get("id")).longValue()); }
            catch (Exception ignored) { /* execute records the actionable error */ }
        }
    }

    public Map<String, Object> execute(long taskId) {
        Map<String, Object> task = one("SELECT * FROM collect_task WHERE id=?", taskId, "采集任务不存在");
        Map<String, Object> source = dataSources.find(((Number) task.get("data_source_id")).longValue());
        if (!truthy(source.get("enabled"))) throw new BizException("数据源已停用");
        String sql = String.valueOf(task.get("execution_content")).trim();
        validateSelect(sql);
        LocalDateTime started = LocalDateTime.now();
        jdbc.update("INSERT INTO collect_log(task_id,started_at,status) VALUES (?,?,?)", taskId, Timestamp.valueOf(started), "RUNNING");
        long logId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        int success = 0, failed = 0;
        String error = null;
        LocalDateTime watermark = asLocalDateTime(task.get("last_sync_time"), LocalDateTime.of(1970,1,1,0,0));
        LocalDateTime maxWatermark = watermark;
        try (Connection connection = DriverManager.getConnection(String.valueOf(source.get("jdbc_url")), String.valueOf(source.get("username")),
                cipher.decrypt(source.get("password_encrypted") == null ? null : String.valueOf(source.get("password_encrypted"))));
             PreparedStatement statement = connection.prepareStatement(sql.replace(":lastSyncTime", "?"))) {
            statement.setTimestamp(1, Timestamp.valueOf(watermark));
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData meta = resultSet.getMetaData();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), resultSet.getObject(i));
                    try {
                        Map<String, Object> transformed = mappingEngine.transform(String.valueOf(task.get("business_type")), String.valueOf(source.get("system_type")), row);
                        transformed.put("source_system", source.get("system_type"));
                        long id = medicalData.upsert(String.valueOf(task.get("business_type")), transformed);
                        validation.validate(String.valueOf(task.get("business_type")), id);
                        success++;
                    } catch (Exception rowError) {
                        failed++;
                        error = rowError.getMessage();
                    }
                    Object marker = row.get(String.valueOf(task.get("incremental_field")).toLowerCase(Locale.ROOT));
                    LocalDateTime markerTime = asLocalDateTime(marker, null);
                    if (markerTime != null && markerTime.isAfter(maxWatermark)) maxWatermark = markerTime;
                }
            }
            LocalDateTime nextRun = LocalDateTime.now().plusSeconds(parseInterval(String.valueOf(task.get("execution_expression"))));
            jdbc.update("UPDATE collect_task SET last_sync_time=?,next_run_time=? WHERE id=?", Timestamp.valueOf(maxWatermark), Timestamp.valueOf(nextRun), taskId);
            jdbc.update("UPDATE collect_log SET ended_at=NOW(),success_count=?,failed_count=?,status=?,error_message=? WHERE id=?",
                    success, failed, failed == 0 ? "SUCCESS" : success > 0 ? "PARTIAL" : "FAILED", error, logId);
            audit.log("SYSTEM", "执行采集任务", "数据源管理", taskId, failed == 0 ? "SUCCESS" : "FAILED", "成功 " + success + "，失败 " + failed);
            return Map.of("successCount", success, "failedCount", failed, "logId", logId);
        } catch (Exception ex) {
            jdbc.update("UPDATE collect_log SET ended_at=NOW(),success_count=?,failed_count=?,status='FAILED',error_message=? WHERE id=?", success, failed, ex.getMessage(), logId);
            audit.log("SYSTEM", "执行采集任务", "数据源管理", taskId, "FAILED", ex.getMessage());
            throw ex instanceof BizException biz ? biz : new BizException("采集失败: " + ex.getMessage());
        }
    }

    private void validateSelect(String sql) {
        if (!sql.regionMatches(true, 0, "SELECT", 0, 6) || FORBIDDEN_SQL.matcher(sql).find()) throw new BizException("采集 SQL 只允许单条 SELECT 查询");
        if (!sql.contains(":lastSyncTime")) throw new BizException("采集 SQL 必须包含 :lastSyncTime 增量参数");
    }

    private long parseInterval(String expression) {
        String text = expression == null ? "30s" : expression.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("m")) return Long.parseLong(text.substring(0, text.length()-1)) * 60;
            if (text.endsWith("h")) return Long.parseLong(text.substring(0, text.length()-1)) * 3600;
            return Long.parseLong(text.replace("s", ""));
        } catch (Exception ex) { return 30; }
    }

    private Map<String, Object> one(String sql, Object id, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, id);
        if (rows.isEmpty()) throw new BizException(message);
        return rows.getFirst();
    }

    static LocalDateTime asLocalDateTime(Object value, LocalDateTime fallback) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        if (value instanceof java.util.Date date) return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        if (value != null) {
            try { return Timestamp.valueOf(String.valueOf(value)).toLocalDateTime(); }
            catch (IllegalArgumentException ignored) { /* use fallback */ }
        }
        return fallback;
    }

    private boolean truthy(Object value) { return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0; }
}

