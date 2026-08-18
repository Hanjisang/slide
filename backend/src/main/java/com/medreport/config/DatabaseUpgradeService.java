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

        upgrades.forEach((table, columns) -> columns.forEach((column, definition) -> addColumn(table, column, definition)));
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
}
