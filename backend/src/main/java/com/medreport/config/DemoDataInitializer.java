package com.medreport.config;

import com.medreport.security.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final String sourceJdbcUrl;

    public DemoDataInitializer(JdbcTemplate jdbc, SecretCipher cipher,
                               @Value("${MOCK_SOURCE_JDBC_URL:jdbc:mysql://localhost:3306/mock_hospital?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}") String sourceJdbcUrl) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.sourceJdbcUrl = sourceJdbcUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='admin'", Integer.class) == 0) {
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,enabled) VALUES (?,?,?,?,1)",
                    "admin", encoder.encode("Admin@123"), "系统管理员", "ADMIN");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM data_source_config WHERE code='MOCK_HIS'", Integer.class) == 0) {
            jdbc.update("""
                    INSERT INTO data_source_config(name,code,connector_type,system_type,database_type,host,port,database_name,username,password_encrypted,jdbc_url,enabled)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,1)
                    """, "Mock HIS", "MOCK_HIS", "DATABASE", "HIS", "MYSQL", "mysql", 3306,
                    "mock_hospital", "medical", cipher.encrypt("medical123"), sourceJdbcUrl);
        }
        Long sourceId = jdbc.queryForObject("SELECT id FROM data_source_config WHERE code='MOCK_HIS'", Long.class);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM collect_task WHERE task_name='Mock HIS 患者采集'", Integer.class) == 0) {
            jdbc.update("""
                    INSERT INTO collect_task(task_name,data_source_id,business_type,execution_expression,execution_content,incremental_field,last_sync_time,enabled,next_run_time)
                    VALUES (?,?,?,?,?,?,?,1,NOW())
                    """, "Mock HIS 患者采集", sourceId, "PATIENT", "30s",
                    "SELECT * FROM mock_his_patient WHERE update_time > :lastSyncTime ORDER BY update_time", "update_time", "1970-01-01 00:00:00");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM pathology_case", Integer.class) == 0) {
            jdbc.update("INSERT INTO pathology_case(pathology_no,specimen_name,clinical_diagnosis,case_status) VALUES (?,?,?,?)",
                    "CASE000001", "结肠组织", "结肠占位待查", "CREATED");
        }
    }
}

