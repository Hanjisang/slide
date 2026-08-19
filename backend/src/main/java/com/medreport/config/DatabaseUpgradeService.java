package com.medreport.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(-100)
public class DatabaseUpgradeService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUpgradeService.class);
    private final JdbcTemplate jdbc;

    public DatabaseUpgradeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Map<String, String>> upgrades = new LinkedHashMap<>();
        upgrades.put("dictionary", Map.of("enabled", "TINYINT NOT NULL DEFAULT 1"));
        upgrades.put("dictionary_item", Map.of("enabled", "TINYINT NOT NULL DEFAULT 1"));
        upgrades.put("pathology_case", Map.of("specimen_type_code", "VARCHAR(50)"));

        Map<String, String> slide = new LinkedHashMap<>();
        slide.put("display_name", "VARCHAR(255)");
        slide.put("specimen_type_code", "VARCHAR(50)");
        slide.put("scan_time", "DATETIME");
        slide.put("storage_target_id", "BIGINT");
        slide.put("storage_class", "VARCHAR(20) NOT NULL DEFAULT 'HOT'");
        slide.put("archive_status", "VARCHAR(20) NOT NULL DEFAULT 'NOT_ARCHIVED'");
        slide.put("archive_target_id", "BIGINT");
        slide.put("archive_object_key", "VARCHAR(1000)");
        slide.put("archived_at", "DATETIME");
        slide.put("archived_by", "VARCHAR(64)");
        slide.put("deleted", "TINYINT NOT NULL DEFAULT 0");
        slide.put("deleted_at", "DATETIME");
        slide.put("deleted_by", "VARCHAR(64)");
        slide.put("version_no", "INT NOT NULL DEFAULT 1");
        upgrades.put("slide_file", slide);

        Map<String, String> template = new LinkedHashMap<>();
        template.put("include_slide", "TINYINT NOT NULL DEFAULT 0");
        template.put("business_type", "VARCHAR(50)");
        template.put("schedule_enabled", "TINYINT NOT NULL DEFAULT 0");
        upgrades.put("report_template", template);

        upgrades.put("alert_rule", Map.of(
                "recovery_threshold", "DECIMAL(12,2)",
                "trigger_count", "INT NOT NULL DEFAULT 1",
                "recovery_count", "INT NOT NULL DEFAULT 1"));
        Map<String, String> alertEvent = new LinkedHashMap<>();
        alertEvent.put("dedup_key", "VARCHAR(191)");
        alertEvent.put("active_key", "VARCHAR(191)");
        alertEvent.put("first_seen_at", "DATETIME");
        alertEvent.put("last_seen_at", "DATETIME");
        alertEvent.put("occurrence_count", "INT NOT NULL DEFAULT 1");
        alertEvent.put("breach_count", "INT NOT NULL DEFAULT 1");
        alertEvent.put("healthy_count", "INT NOT NULL DEFAULT 0");
        alertEvent.put("recovered_at", "DATETIME");
        alertEvent.put("recovery_message", "VARCHAR(1000)");
        upgrades.put("alert_event", alertEvent);

        upgrades.forEach((table, columns) -> columns.forEach((column, definition) -> addColumn(table, column, definition)));
        jdbc.update("""
                UPDATE alert_event older JOIN alert_event newer
                  ON older.event_type=newer.event_type AND older.source_type <=> newer.source_type AND older.source_id <=> newer.source_id
                 AND older.id<newer.id AND older.status IN ('PENDING','OPEN','ACKNOWLEDGED') AND newer.status IN ('PENDING','OPEN','ACKNOWLEDGED')
                SET older.status='CLOSED',older.closed_by='SYSTEM',older.closed_at=NOW()
                """);
        jdbc.update("""
                UPDATE alert_event SET dedup_key=CONCAT(event_type,'|',COALESCE(source_type,'_'),'|',COALESCE(source_id,'_')),
                active_key=CASE WHEN status IN ('PENDING','OPEN','ACKNOWLEDGED') THEN CONCAT(event_type,'|',COALESCE(source_type,'_'),'|',COALESCE(source_id,'_')) ELSE NULL END,
                first_seen_at=COALESCE(first_seen_at,created_at),last_seen_at=COALESCE(last_seen_at,updated_at)
                WHERE dedup_key IS NULL
                """);
        addIndex("alert_event", "uk_alert_event_active_key", "UNIQUE", "active_key");
        jdbc.update("""
                UPDATE alert_rule SET
                  recovery_threshold=CASE rule_type
                    WHEN 'CPU_USAGE_HIGH' THEN 85 WHEN 'MEMORY_USAGE_HIGH' THEN 85 WHEN 'DISK_USAGE_HIGH' THEN 80
                    WHEN 'JVM_HEAP_USAGE_HIGH' THEN 85 WHEN 'STORAGE_USAGE_HIGH' THEN 80 ELSE 0 END,
                  trigger_count=CASE WHEN rule_type IN ('COLLECT_FAILED','SLIDE_PARSE_FAILED','ARCHIVE_FAILED','BACKUP_FAILED','REPORT_FAILED','PARSER_FORMAT_FAILED') THEN 1 ELSE 2 END,
                  recovery_count=CASE WHEN rule_type IN ('COLLECT_FAILED','SLIDE_PARSE_FAILED','ARCHIVE_FAILED','BACKUP_FAILED','REPORT_FAILED','PARSER_FORMAT_FAILED') THEN 1 ELSE 2 END
                WHERE rule_type IN ('CPU_USAGE_HIGH','MEMORY_USAGE_HIGH','DISK_USAGE_HIGH','JVM_HEAP_USAGE_HIGH','MYSQL_DOWN','MINIO_DOWN',
                  'SLIDE_WORKER_DOWN','GO_PARSER_DOWN','STORAGE_TARGET_DOWN','STORAGE_READ_FAILED','STORAGE_WRITE_FAILED','STORAGE_USAGE_HIGH',
                  'COLLECT_FAILED','SLIDE_PARSE_FAILED','ARCHIVE_FAILED','BACKUP_FAILED','REPORT_FAILED','COLLECT_STUCK','SLIDE_PARSE_STUCK',
                  'ARCHIVE_STUCK','BACKUP_STUCK','REPORT_STUCK','COLLECT_QUEUE_BACKLOG','SLIDE_QUEUE_BACKLOG','REPORT_QUEUE_BACKLOG',
                  'ARCHIVE_QUEUE_BACKLOG','BACKUP_QUEUE_BACKLOG','PARSER_FORMAT_FAILED')
                """);
        jdbc.update("UPDATE slide_file SET display_name=file_name WHERE display_name IS NULL");
        log.info("Database schema is ready for v0.2.0");
    }

    private void addColumn(String table, String column, String definition) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?
                """, Integer.class, table, column);
        if (count != null && count == 0) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void addIndex(String table, String index, String type, String columns) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?
                """, Integer.class, table, index);
        if (count != null && count == 0) jdbc.execute("CREATE " + type + " INDEX " + index + " ON " + table + "(" + columns + ")");
    }
}
