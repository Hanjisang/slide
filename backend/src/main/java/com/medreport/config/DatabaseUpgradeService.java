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
        upgrades.put("report_batch", Map.of("report_spec_id", "BIGINT", "report_job_id", "BIGINT", "precheck_status", "VARCHAR(30)", "transfer_status", "VARCHAR(30)"));
        Map<String,String> plan = new LinkedHashMap<>();
        plan.put("spec_id", "BIGINT"); plan.put("priority", "INT NOT NULL DEFAULT 5");
        plan.put("max_retry", "INT NOT NULL DEFAULT 4"); plan.put("retry_policy", "VARCHAR(30) NOT NULL DEFAULT 'FIXED'");
        plan.put("execution_timeout_minutes", "INT NOT NULL DEFAULT 60"); plan.put("concurrency_policy", "VARCHAR(20) NOT NULL DEFAULT 'QUEUE'");
        upgrades.put("report_plan", plan);

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
        createV04Tables();
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
        jdbc.update("INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES ('REPORT_SPEC_MANAGE','上报规范管理'),('REPORT_PLAN_MANAGE','上报计划管理'),('REPORT_PRECHECK','上报预审核'),('REPORT_PRECHECK_OVERRIDE','预审核覆盖'),('REPORT_TRANSFER_MANAGE','传输管理'),('REPORT_ENDPOINT_MANAGE','上报端点管理')");
        jdbc.update("INSERT IGNORE INTO sys_role_permission(role_id,permission_id) SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.permission_code IN ('REPORT_PRECHECK','REPORT_TRANSFER_MANAGE') WHERE r.role_code='OPERATOR'");
        jdbc.update("INSERT IGNORE INTO sys_role_permission(role_id,permission_id) SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.permission_code IN ('REPORT_PRECHECK','REPORT_PRECHECK_OVERRIDE','REPORT_SPEC_MANAGE','REPORT_PLAN_MANAGE','REPORT_ENDPOINT_MANAGE','REPORT_TRANSFER_MANAGE') WHERE r.role_code='AUDITOR'");
        log.info("Database schema is ready for v0.4.0");
    }

    private void createV04Tables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_spec (id BIGINT PRIMARY KEY AUTO_INCREMENT,spec_code VARCHAR(64) NOT NULL,spec_name VARCHAR(100) NOT NULL,authority_name VARCHAR(200),region_code VARCHAR(50),business_type VARCHAR(50) NOT NULL DEFAULT 'PATHOLOGY',version VARCHAR(30) NOT NULL,effective_from DATE,effective_to DATE,file_format VARCHAR(20) NOT NULL DEFAULT 'JSON',encoding VARCHAR(30) NOT NULL DEFAULT 'UTF-8',compression_type VARCHAR(20) NOT NULL DEFAULT 'NONE',file_naming_rule VARCHAR(255),transport_mode VARCHAR(30) NOT NULL DEFAULT 'HTTP',default_frequency VARCHAR(30),demo TINYINT NOT NULL DEFAULT 1,enabled TINYINT NOT NULL DEFAULT 1,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,UNIQUE KEY uk_report_spec_version(spec_code,version))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_spec_field (id BIGINT PRIMARY KEY AUTO_INCREMENT,spec_id BIGINT NOT NULL,dataset_code VARCHAR(50) NOT NULL,field_code VARCHAR(100) NOT NULL,field_name VARCHAR(100) NOT NULL,source_expression VARCHAR(255),data_type VARCHAR(30) NOT NULL DEFAULT 'STRING',required TINYINT NOT NULL DEFAULT 0,max_length INT,dictionary_type VARCHAR(100),format_pattern VARCHAR(100),transform_type VARCHAR(30),default_value VARCHAR(255),sort_order INT NOT NULL DEFAULT 0,enabled TINYINT NOT NULL DEFAULT 1,UNIQUE KEY uk_report_spec_field(spec_id,dataset_code,field_code))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_endpoint (id BIGINT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(100) NOT NULL,endpoint_url VARCHAR(1000),transport_mode VARCHAR(30) NOT NULL DEFAULT 'HTTP',auth_type VARCHAR(30) NOT NULL DEFAULT 'NONE',username VARCHAR(100),password_encrypted VARCHAR(500),token_encrypted VARCHAR(1000),client_cert_path VARCHAR(1000),client_key_path VARCHAR(1000),trust_store_path VARCHAR(1000),tls_verify TINYINT NOT NULL DEFAULT 1,enabled TINYINT NOT NULL DEFAULT 1,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_job (id BIGINT PRIMARY KEY AUTO_INCREMENT,job_no VARCHAR(100) NOT NULL UNIQUE,plan_id BIGINT,spec_id BIGINT,trigger_type VARCHAR(30) NOT NULL,scheduled_at DATETIME NOT NULL,priority INT NOT NULL DEFAULT 5,status VARCHAR(30) NOT NULL DEFAULT 'WAITING',started_at DATETIME,finished_at DATETIME,batch_id BIGINT,attempt_count INT NOT NULL DEFAULT 0,result_message VARCHAR(1000),error_message VARCHAR(1000),created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,UNIQUE KEY uk_report_job_schedule(plan_id,scheduled_at),KEY idx_report_job_queue(status,priority,scheduled_at))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_job_item (id BIGINT PRIMARY KEY AUTO_INCREMENT,job_id BIGINT NOT NULL,business_type VARCHAR(50) NOT NULL,business_id BIGINT NOT NULL,snapshot_version VARCHAR(64) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'SNAPSHOT',created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE KEY uk_report_job_item(job_id,business_type,business_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_precheck_rule (id BIGINT PRIMARY KEY AUTO_INCREMENT,spec_id BIGINT NOT NULL,dataset_code VARCHAR(50),field_code VARCHAR(100),rule_type VARCHAR(30) NOT NULL,rule_config VARCHAR(500),severity VARCHAR(20) NOT NULL DEFAULT 'ERROR',error_message VARCHAR(255) NOT NULL,suggestion VARCHAR(500),enabled TINYINT NOT NULL DEFAULT 1,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_precheck (id BIGINT PRIMARY KEY AUTO_INCREMENT,job_id BIGINT NOT NULL,batch_id BIGINT,spec_id BIGINT,status VARCHAR(30) NOT NULL,total_count INT NOT NULL DEFAULT 0,passed_count INT NOT NULL DEFAULT 0,failed_count INT NOT NULL DEFAULT 0,warning_count INT NOT NULL DEFAULT 0,started_at DATETIME,finished_at DATETIME,operator VARCHAR(64),created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_precheck_issue (id BIGINT PRIMARY KEY AUTO_INCREMENT,precheck_id BIGINT NOT NULL,business_type VARCHAR(50),business_id BIGINT,dataset_code VARCHAR(50),field_code VARCHAR(100),rule_id BIGINT,rule_type VARCHAR(30),current_value TEXT,error_message VARCHAR(500) NOT NULL,suggestion VARCHAR(500),severity VARCHAR(20) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'OPEN',handled_by VARCHAR(64),handled_at DATETIME,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_data_override (id BIGINT PRIMARY KEY AUTO_INCREMENT,job_id BIGINT NOT NULL,batch_id BIGINT,business_type VARCHAR(50),business_id BIGINT,dataset_code VARCHAR(50),field_path VARCHAR(255),old_value TEXT,new_value TEXT,reason VARCHAR(500),operator VARCHAR(64),created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_transfer_session (id BIGINT PRIMARY KEY AUTO_INCREMENT,job_id BIGINT,batch_id BIGINT,sender_type VARCHAR(30) NOT NULL,endpoint VARCHAR(1000),remote_upload_id VARCHAR(255),file_path VARCHAR(1000) NOT NULL,total_bytes BIGINT NOT NULL,uploaded_bytes BIGINT NOT NULL DEFAULT 0,chunk_size INT NOT NULL DEFAULT 8388608,total_chunks INT NOT NULL DEFAULT 0,completed_chunks INT NOT NULL DEFAULT 0,whole_sha256 CHAR(64),status VARCHAR(30) NOT NULL DEFAULT 'INIT',started_at DATETIME,last_activity_at DATETIME,completed_at DATETIME,retry_count INT NOT NULL DEFAULT 0,last_error VARCHAR(1000),created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS report_transfer_chunk (id BIGINT PRIMARY KEY AUTO_INCREMENT,session_id BIGINT NOT NULL,chunk_index INT NOT NULL,offset_bytes BIGINT NOT NULL,chunk_size INT NOT NULL,sha256 CHAR(64),status VARCHAR(20) NOT NULL DEFAULT 'PENDING',attempt_count INT NOT NULL DEFAULT 0,uploaded_at DATETIME,last_error VARCHAR(1000),UNIQUE KEY uk_transfer_chunk(session_id,chunk_index))");
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
