CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
  display_name VARCHAR(100) NOT NULL, role VARCHAR(20) NOT NULL DEFAULT 'VIEWER', enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS system_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, config_key VARCHAR(100) NOT NULL UNIQUE, config_value VARCHAR(500), description VARCHAR(255),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS operation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64), operation VARCHAR(100) NOT NULL, module VARCHAR(50) NOT NULL,
  business_id VARCHAR(64), result VARCHAR(20) NOT NULL, detail VARCHAR(500), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, role_code VARCHAR(30) NOT NULL UNIQUE, role_name VARCHAR(100) NOT NULL,
  description VARCHAR(255), enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sys_permission (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, permission_code VARCHAR(50) NOT NULL UNIQUE, permission_name VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sys_role_permission (
  role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL, PRIMARY KEY(role_id, permission_id)
);
CREATE TABLE IF NOT EXISTS data_source_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, code VARCHAR(50) NOT NULL UNIQUE,
  connector_type VARCHAR(20) NOT NULL, system_type VARCHAR(20) NOT NULL, database_type VARCHAR(20), host VARCHAR(255), port INT,
  database_name VARCHAR(100), username VARCHAR(100), password_encrypted VARCHAR(500), jdbc_url VARCHAR(1000), api_url VARCHAR(1000), file_path VARCHAR(1000),
  enabled TINYINT NOT NULL DEFAULT 1, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS collect_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, task_name VARCHAR(100) NOT NULL, data_source_id BIGINT NOT NULL, business_type VARCHAR(30) NOT NULL,
  execution_expression VARCHAR(100) NOT NULL DEFAULT '30s', execution_content TEXT NOT NULL, incremental_field VARCHAR(100) DEFAULT 'update_time',
  last_sync_time DATETIME NULL, enabled TINYINT NOT NULL DEFAULT 1, next_run_time DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS collect_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, task_id BIGINT NOT NULL, started_at DATETIME NOT NULL, ended_at DATETIME,
  success_count INT NOT NULL DEFAULT 0, failed_count INT NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL, error_message VARCHAR(1000),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS patient (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, source_system VARCHAR(30) NOT NULL, source_id VARCHAR(100), patient_no VARCHAR(100), name VARCHAR(100),
  gender VARCHAR(10), birthday DATE, age INT, id_card VARCHAR(50), phone VARCHAR(50), quality_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_patient_source (source_system, source_id)
);
CREATE TABLE IF NOT EXISTS visit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, source_system VARCHAR(30), source_id VARCHAR(100), patient_id BIGINT, visit_type VARCHAR(20), visit_no VARCHAR(100),
  department_code VARCHAR(50), department_name VARCHAR(100), doctor_code VARCHAR(50), doctor_name VARCHAR(100), admission_time DATETIME, discharge_time DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_visit_source (source_system, source_id)
);
CREATE TABLE IF NOT EXISTS diagnosis (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, source_system VARCHAR(30), source_id VARCHAR(100), patient_id BIGINT, visit_id BIGINT,
  diagnosis_code VARCHAR(50), diagnosis_name VARCHAR(255), diagnosis_type VARCHAR(30), diagnosis_time DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_diagnosis_source (source_system, source_id)
);
CREATE TABLE IF NOT EXISTS lab_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, source_system VARCHAR(30), source_id VARCHAR(100), patient_id BIGINT, visit_id BIGINT,
  item_code VARCHAR(50), item_name VARCHAR(255), result_value VARCHAR(500), result_unit VARCHAR(50), reference_range VARCHAR(100), abnormal_flag VARCHAR(20), result_time DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_lab_source (source_system, source_id)
);
CREATE TABLE IF NOT EXISTS exam_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, source_system VARCHAR(30), source_id VARCHAR(100), patient_id BIGINT, visit_id BIGINT,
  exam_code VARCHAR(50), exam_name VARCHAR(255), exam_part VARCHAR(100), exam_result TEXT, exam_conclusion TEXT, exam_time DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_exam_source (source_system, source_id)
);
CREATE TABLE IF NOT EXISTS medical_operation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, source_system VARCHAR(30), source_id VARCHAR(100), patient_id BIGINT, visit_id BIGINT,
  operation_code VARCHAR(50), operation_name VARCHAR(255), operation_time DATETIME, operator_name VARCHAR(100),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_operation_source (source_system, source_id)
);
CREATE TABLE IF NOT EXISTS medication (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, source_system VARCHAR(30), source_id VARCHAR(100), patient_id BIGINT, visit_id BIGINT,
  drug_code VARCHAR(50), drug_name VARCHAR(255), dosage VARCHAR(50), unit VARCHAR(30), frequency VARCHAR(50), route VARCHAR(50), start_time DATETIME, end_time DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_medication_source (source_system, source_id)
);
CREATE TABLE IF NOT EXISTS mapping_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, business_type VARCHAR(30) NOT NULL, source_system VARCHAR(30), enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS mapping_field (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, template_id BIGINT NOT NULL, source_field VARCHAR(100), target_field VARCHAR(100) NOT NULL,
  rule_type VARCHAR(30) NOT NULL, rule_config VARCHAR(500), sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS dictionary (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, dict_type VARCHAR(50) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL, description VARCHAR(255),
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS dictionary_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, dictionary_id BIGINT NOT NULL, source_value VARCHAR(100) NOT NULL, target_value VARCHAR(100) NOT NULL, description VARCHAR(255),
  enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_dictionary_item (dictionary_id, source_value)
);
CREATE TABLE IF NOT EXISTS validation_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, business_type VARCHAR(30) NOT NULL, field_name VARCHAR(100) NOT NULL, rule_type VARCHAR(30) NOT NULL,
  rule_config VARCHAR(500), error_message VARCHAR(255) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS validation_error (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, business_type VARCHAR(30) NOT NULL, business_id BIGINT NOT NULL, patient_id BIGINT,
  field_name VARCHAR(100) NOT NULL, current_value TEXT, rule_id BIGINT NOT NULL, rule_type VARCHAR(30) NOT NULL, error_message VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING', handled_by VARCHAR(64), handled_at DATETIME, handle_note VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS validation_handle_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, error_id BIGINT NOT NULL, username VARCHAR(64), old_value TEXT, new_value TEXT, action VARCHAR(30) NOT NULL,
  result VARCHAR(20) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS pathology_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, patient_id BIGINT, visit_id BIGINT, pathology_no VARCHAR(100) NOT NULL UNIQUE, specimen_name VARCHAR(255),
  specimen_type_code VARCHAR(50), clinical_diagnosis TEXT, pathology_diagnosis TEXT, case_status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS slide_file (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, case_id BIGINT NOT NULL, slide_no VARCHAR(100) NOT NULL, file_name VARCHAR(255) NOT NULL,
  display_name VARCHAR(255), specimen_type_code VARCHAR(50), scan_time DATETIME,
  file_extension VARCHAR(20) NOT NULL, file_format VARCHAR(30) NOT NULL, file_size BIGINT NOT NULL, bucket_name VARCHAR(100) NOT NULL, object_key VARCHAR(1000) NOT NULL,
  adapter_type VARCHAR(50), sdk_status VARCHAR(30), width BIGINT, height BIGINT, level_count INT, levels_json TEXT, md5 VARCHAR(32),
  storage_target_id BIGINT, storage_class VARCHAR(20) NOT NULL DEFAULT 'HOT', archive_status VARCHAR(20) NOT NULL DEFAULT 'NOT_ARCHIVED',
  archive_target_id BIGINT, archive_object_key VARCHAR(1000), archived_at DATETIME, archived_by VARCHAR(64),
  deleted TINYINT NOT NULL DEFAULT 0, deleted_at DATETIME, deleted_by VARCHAR(64), version_no INT NOT NULL DEFAULT 1,
  status VARCHAR(20) NOT NULL, error_message VARCHAR(1000), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS report_template (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, report_type VARCHAR(50) NOT NULL, format VARCHAR(20) NOT NULL,
  sender_type VARCHAR(20) NOT NULL, endpoint VARCHAR(1000), include_slide TINYINT NOT NULL DEFAULT 0,
  business_type VARCHAR(50), schedule_enabled TINYINT NOT NULL DEFAULT 0, enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS report_batch (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, batch_no VARCHAR(100) NOT NULL UNIQUE, template_id BIGINT, report_type VARCHAR(50) NOT NULL, format VARCHAR(20) NOT NULL,
  total_count INT NOT NULL DEFAULT 0, success_count INT NOT NULL DEFAULT 0, failed_count INT NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL,
  file_path VARCHAR(1000), sender_type VARCHAR(20), endpoint VARCHAR(1000), retry_count INT NOT NULL DEFAULT 0, next_retry_time DATETIME,
  last_error VARCHAR(1000), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  reported_at DATETIME
);
CREATE TABLE IF NOT EXISTS report_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, batch_id BIGINT NOT NULL, business_type VARCHAR(30) NOT NULL, business_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL, error_message VARCHAR(1000), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS storage_target (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, storage_type VARCHAR(20) NOT NULL,
  endpoint VARCHAR(500), access_key_encrypted VARCHAR(1000), secret_key_encrypted VARCHAR(1000), bucket VARCHAR(100) NOT NULL,
  base_path VARCHAR(500), storage_class VARCHAR(20) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS archive_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, slide_id BIGINT NOT NULL, source_storage_id BIGINT NOT NULL, target_storage_id BIGINT NOT NULL,
  source_object_key VARCHAR(1000) NOT NULL, target_object_key VARCHAR(1000) NOT NULL, status VARCHAR(20) NOT NULL, progress INT NOT NULL DEFAULT 0,
  source_md5 VARCHAR(32), target_md5 VARCHAR(32), note VARCHAR(500), started_at DATETIME, finished_at DATETIME,
  operator VARCHAR(64), error_message VARCHAR(1000), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS archive_policy (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, condition_type VARCHAR(30) NOT NULL,
  condition_value VARCHAR(100) NOT NULL, target_storage_id BIGINT NOT NULL, enabled TINYINT NOT NULL DEFAULT 1, last_run_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS file_asset (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, file_type VARCHAR(30) NOT NULL, business_type VARCHAR(30), business_id BIGINT,
  file_name VARCHAR(255) NOT NULL, display_name VARCHAR(255), storage_target_id BIGINT NOT NULL, object_key VARCHAR(1000) NOT NULL,
  file_size BIGINT NOT NULL, md5 VARCHAR(32) NOT NULL, current_version INT NOT NULL DEFAULT 1, deleted TINYINT NOT NULL DEFAULT 0,
  deleted_at DATETIME, deleted_by VARCHAR(64), created_by VARCHAR(64), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS file_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, file_id BIGINT NOT NULL, version_no INT NOT NULL, storage_target_id BIGINT NOT NULL,
  object_key VARCHAR(1000) NOT NULL, file_size BIGINT NOT NULL, md5 VARCHAR(32) NOT NULL, created_by VARCHAR(64),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_file_version(file_id, version_no)
);
CREATE TABLE IF NOT EXISTS backup_policy (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, source_storage_id BIGINT NOT NULL, target_storage_id BIGINT NOT NULL,
  frequency VARCHAR(20) NOT NULL, cron_expression VARCHAR(100), enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS backup_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, file_id BIGINT NOT NULL, source_storage_id BIGINT NOT NULL, target_storage_id BIGINT NOT NULL,
  target_object_key VARCHAR(1000), status VARCHAR(20) NOT NULL, progress INT NOT NULL DEFAULT 0, source_md5 VARCHAR(32), target_md5 VARCHAR(32),
  started_at DATETIME, finished_at DATETIME, error_message VARCHAR(1000), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS report_plan (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, template_id BIGINT NOT NULL, frequency_type VARCHAR(20) NOT NULL,
  cron_expression VARCHAR(100), enabled TINYINT NOT NULL DEFAULT 1, last_run_time DATETIME, next_run_time DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS alert_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, rule_type VARCHAR(50) NOT NULL, threshold_value DECIMAL(12,2),
  recovery_threshold DECIMAL(12,2), trigger_count INT NOT NULL DEFAULT 1, recovery_count INT NOT NULL DEFAULT 1,
  severity VARCHAR(20) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS alert_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, rule_id BIGINT, event_type VARCHAR(50) NOT NULL, severity VARCHAR(20) NOT NULL,
  source_type VARCHAR(30), source_id BIGINT, message VARCHAR(1000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  acknowledged_by VARCHAR(64), acknowledged_at DATETIME, closed_by VARCHAR(64), closed_at DATETIME,
  dedup_key VARCHAR(191), active_key VARCHAR(191), first_seen_at DATETIME, last_seen_at DATETIME,
  occurrence_count INT NOT NULL DEFAULT 1, breach_count INT NOT NULL DEFAULT 1, healthy_count INT NOT NULL DEFAULT 0,
  recovered_at DATETIME, recovery_message VARCHAR(1000),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_alert_event_active_key(active_key)
);

CREATE DATABASE IF NOT EXISTS mock_hospital CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS mock_hospital.mock_his_patient (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, patient_no VARCHAR(100), patient_name VARCHAR(100), sex_code VARCHAR(10), birthday DATE, age INT,
  id_card VARCHAR(50), phone VARCHAR(50), update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS mock_hospital.mock_his_visit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, patient_id BIGINT, visit_no VARCHAR(100), visit_type VARCHAR(20), department_name VARCHAR(100), doctor_name VARCHAR(100),
  admission_time DATETIME, discharge_time DATETIME, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS mock_hospital.mock_emr_diagnosis (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, patient_id BIGINT, visit_id BIGINT, diagnosis_code VARCHAR(50), diagnosis_name VARCHAR(255), diagnosis_type VARCHAR(30),
  diagnosis_time DATETIME, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS mock_hospital.mock_lis_result (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, patient_id BIGINT, visit_id BIGINT, item_code VARCHAR(50), item_name VARCHAR(255), result_value VARCHAR(100),
  result_unit VARCHAR(50), reference_range VARCHAR(100), abnormal_flag VARCHAR(20), result_time DATETIME,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT IGNORE INTO system_config(config_key, config_value, description) VALUES
('platform.name', '医疗数据及数字病理上报平台', '平台名称'), ('collect.enabled', 'true', '是否启用自动采集'), ('report.retry.enabled', 'true', '是否启用上报重试');
INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled)
SELECT 'CPU 使用率过高','CPU_USAGE_HIGH',90,'WARNING',1 WHERE NOT EXISTS(SELECT 1 FROM alert_rule WHERE rule_type='CPU_USAGE_HIGH');
INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled)
SELECT '内存使用率过高','MEMORY_USAGE_HIGH',90,'WARNING',1 WHERE NOT EXISTS(SELECT 1 FROM alert_rule WHERE rule_type='MEMORY_USAGE_HIGH');
INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled)
SELECT '磁盘使用率过高','DISK_USAGE_HIGH',85,'WARNING',1 WHERE NOT EXISTS(SELECT 1 FROM alert_rule WHERE rule_type='DISK_USAGE_HIGH');
INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled)
SELECT 'JVM Heap 使用率过高','JVM_HEAP_USAGE_HIGH',90,'WARNING',1 WHERE NOT EXISTS(SELECT 1 FROM alert_rule WHERE rule_type='JVM_HEAP_USAGE_HIGH');
INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled)
SELECT 'MySQL 不可用','MYSQL_DOWN',1,'CRITICAL',1 WHERE NOT EXISTS(SELECT 1 FROM alert_rule WHERE rule_type='MYSQL_DOWN');
INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled)
SELECT 'MinIO 不可用','MINIO_DOWN',1,'CRITICAL',1 WHERE NOT EXISTS(SELECT 1 FROM alert_rule WHERE rule_type='MINIO_DOWN');
INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled)
SELECT 'Slide Worker 不可用','SLIDE_WORKER_DOWN',1,'CRITICAL',1 WHERE NOT EXISTS(SELECT 1 FROM alert_rule WHERE rule_type='SLIDE_WORKER_DOWN');
INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled)
SELECT 'Go Parser 不可用','GO_PARSER_DOWN',1,'WARNING',1 WHERE NOT EXISTS(SELECT 1 FROM alert_rule WHERE rule_type='GO_PARSER_DOWN');
INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled)
SELECT label,type_name,threshold_value,severity,1 FROM (
  SELECT '存储目标不可用' label,'STORAGE_TARGET_DOWN' type_name,1 threshold_value,0 recovery_threshold,2 trigger_count,2 recovery_count,'CRITICAL' severity UNION ALL
  SELECT '存储读取失败','STORAGE_READ_FAILED',1,0,2,2,'CRITICAL' UNION ALL
  SELECT '存储写入失败','STORAGE_WRITE_FAILED',1,0,2,2,'CRITICAL' UNION ALL
  SELECT '存储使用率过高','STORAGE_USAGE_HIGH',85,80,2,2,'WARNING' UNION ALL
  SELECT '采集失败','COLLECT_FAILED',1,0,1,1,'WARNING' UNION ALL
  SELECT '切片解析失败','SLIDE_PARSE_FAILED',1,0,1,1,'WARNING' UNION ALL
  SELECT '归档失败','ARCHIVE_FAILED',1,0,1,1,'WARNING' UNION ALL
  SELECT '备份失败','BACKUP_FAILED',1,0,1,1,'WARNING' UNION ALL
  SELECT '上报失败','REPORT_FAILED',1,0,1,1,'WARNING' UNION ALL
  SELECT '采集任务卡住','COLLECT_STUCK',30,0,2,2,'WARNING' UNION ALL
  SELECT '切片解析卡住','SLIDE_PARSE_STUCK',30,0,2,2,'WARNING' UNION ALL
  SELECT '归档任务卡住','ARCHIVE_STUCK',30,0,2,2,'WARNING' UNION ALL
  SELECT '备份任务卡住','BACKUP_STUCK',30,0,2,2,'WARNING' UNION ALL
  SELECT '上报任务卡住','REPORT_STUCK',30,0,2,2,'WARNING' UNION ALL
  SELECT '采集队列积压','COLLECT_QUEUE_BACKLOG',100,0,2,2,'WARNING' UNION ALL
  SELECT '切片队列积压','SLIDE_QUEUE_BACKLOG',100,0,2,2,'WARNING' UNION ALL
  SELECT '上报队列积压','REPORT_QUEUE_BACKLOG',100,0,2,2,'WARNING' UNION ALL
  SELECT '归档队列积压','ARCHIVE_QUEUE_BACKLOG',100,0,2,2,'WARNING' UNION ALL
  SELECT '备份队列积压','BACKUP_QUEUE_BACKLOG',100,0,2,2,'WARNING' UNION ALL
  SELECT 'Parser 格式连续失败','PARSER_FORMAT_FAILED',5,0,1,1,'WARNING'
) d WHERE NOT EXISTS(SELECT 1 FROM alert_rule WHERE rule_type=d.type_name);
INSERT IGNORE INTO dictionary(id, dict_type, name, description) VALUES (1, 'SEX', '性别字典', '医院性别编码标准化');
INSERT IGNORE INTO dictionary(dict_type, name, description) VALUES
('SPECIMEN_TYPE', '标本类型', '数字病理标本类型'),
('SLIDE_FORMAT', '切片格式', '数字切片格式'),
('DIAGNOSIS_TYPE', '诊断类型', '诊断类型基础数据');
INSERT IGNORE INTO dictionary_item(dictionary_id, source_value, target_value, description)
SELECT id, 'TISSUE', 'TISSUE', '组织标本' FROM dictionary WHERE dict_type='SPECIMEN_TYPE';
INSERT IGNORE INTO dictionary_item(dictionary_id, source_value, target_value, description)
SELECT id, 'SVS', 'SVS', 'Aperio SVS' FROM dictionary WHERE dict_type='SLIDE_FORMAT';
INSERT IGNORE INTO dictionary_item(dictionary_id, source_value, target_value, description)
SELECT id, 'PRIMARY', 'PRIMARY', '主要诊断' FROM dictionary WHERE dict_type='DIAGNOSIS_TYPE';

INSERT IGNORE INTO sys_role(role_code,role_name,description) VALUES
('ADMIN','管理员','全部权限'),('OPERATOR','操作员','采集、质量、切片、归档和上报'),
('AUDITOR','审计员','数据、日志、批次和监控只读'),('VIEWER','查看者','只读权限');
INSERT IGNORE INTO sys_permission(permission_code,permission_name) VALUES
('SLIDE_VIEW','查看切片'),('SLIDE_UPLOAD','上传切片'),('SLIDE_DOWNLOAD','下载切片'),('SLIDE_RENAME','重命名切片'),
('SLIDE_DELETE','删除切片'),('SLIDE_ARCHIVE','归档切片'),('DATA_VIEW','查看医疗数据'),('DATA_EDIT','编辑医疗数据'),
('QUALITY_MANAGE','质量管理'),('REPORT_GENERATE','生成上报'),('REPORT_SEND','发送上报'),('FILE_MANAGE','文件管理'),
('DICT_MANAGE','字典管理'),('USER_MANAGE','用户管理'),('SYSTEM_CONFIG','系统配置'),('LOG_VIEW','查看日志'),('MONITOR_VIEW','查看监控');
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r CROSS JOIN sys_permission p WHERE r.role_code='ADMIN';
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.permission_code IN
('SLIDE_VIEW','SLIDE_UPLOAD','SLIDE_DOWNLOAD','SLIDE_RENAME','SLIDE_DELETE','SLIDE_ARCHIVE','DATA_VIEW','DATA_EDIT','QUALITY_MANAGE','REPORT_GENERATE','REPORT_SEND','FILE_MANAGE')
WHERE r.role_code='OPERATOR';
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.permission_code IN
('SLIDE_VIEW','SLIDE_DOWNLOAD','DATA_VIEW','LOG_VIEW','MONITOR_VIEW') WHERE r.role_code='AUDITOR';
INSERT IGNORE INTO sys_role_permission(role_id,permission_id)
SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.permission_code IN
('SLIDE_VIEW','DATA_VIEW') WHERE r.role_code='VIEWER';
INSERT IGNORE INTO dictionary_item(dictionary_id, source_value, target_value, description) VALUES
(1, '1', 'M', '男'), (1, '2', 'F', '女'), (1, '9', 'U', '未知');
INSERT INTO mapping_template(id, name, business_type, source_system, enabled)
SELECT 1, 'Mock HIS 患者映射', 'PATIENT', 'HIS', 1 WHERE NOT EXISTS (SELECT 1 FROM mapping_template WHERE id=1);
INSERT INTO mapping_field(template_id, source_field, target_field, rule_type, rule_config, sort_order)
SELECT 1, 'id', 'source_id', 'DIRECT', NULL, 1 WHERE NOT EXISTS (SELECT 1 FROM mapping_field WHERE template_id=1 AND target_field='source_id');
INSERT INTO mapping_field(template_id, source_field, target_field, rule_type, rule_config, sort_order)
SELECT 1, 'patient_no', 'patient_no', 'TRIM', NULL, 2 WHERE NOT EXISTS (SELECT 1 FROM mapping_field WHERE template_id=1 AND target_field='patient_no');
INSERT INTO mapping_field(template_id, source_field, target_field, rule_type, rule_config, sort_order)
SELECT 1, 'patient_name', 'name', 'TRIM', NULL, 3 WHERE NOT EXISTS (SELECT 1 FROM mapping_field WHERE template_id=1 AND target_field='name');
INSERT INTO mapping_field(template_id, source_field, target_field, rule_type, rule_config, sort_order)
SELECT 1, 'sex_code', 'gender', 'DICTIONARY', 'SEX', 4 WHERE NOT EXISTS (SELECT 1 FROM mapping_field WHERE template_id=1 AND target_field='gender');
INSERT INTO mapping_field(template_id, source_field, target_field, rule_type, rule_config, sort_order)
SELECT 1, 'birthday', 'birthday', 'DATE_FORMAT', 'yyyy-MM-dd', 5 WHERE NOT EXISTS (SELECT 1 FROM mapping_field WHERE template_id=1 AND target_field='birthday');
INSERT INTO mapping_field(template_id, source_field, target_field, rule_type, rule_config, sort_order)
SELECT 1, 'age', 'age', 'NUMBER', NULL, 6 WHERE NOT EXISTS (SELECT 1 FROM mapping_field WHERE template_id=1 AND target_field='age');
INSERT INTO mapping_field(template_id, source_field, target_field, rule_type, rule_config, sort_order)
SELECT 1, 'phone', 'phone', 'TRIM', NULL, 7 WHERE NOT EXISTS (SELECT 1 FROM mapping_field WHERE template_id=1 AND target_field='phone');
INSERT INTO validation_rule(business_type, field_name, rule_type, rule_config, error_message, enabled)
SELECT 'PATIENT', 'name', 'NOT_NULL', NULL, '患者姓名不能为空', 1 WHERE NOT EXISTS (SELECT 1 FROM validation_rule WHERE business_type='PATIENT' AND field_name='name');
INSERT INTO validation_rule(business_type, field_name, rule_type, rule_config, error_message, enabled)
SELECT 'PATIENT', 'gender', 'ENUM', 'M,F,U', '患者性别不合法', 1 WHERE NOT EXISTS (SELECT 1 FROM validation_rule WHERE business_type='PATIENT' AND field_name='gender');
INSERT INTO validation_rule(business_type, field_name, rule_type, rule_config, error_message, enabled)
SELECT 'PATIENT', 'age', 'RANGE', '0,150', '患者年龄超出允许范围', 1 WHERE NOT EXISTS (SELECT 1 FROM validation_rule WHERE business_type='PATIENT' AND field_name='age');
INSERT INTO validation_rule(business_type, field_name, rule_type, rule_config, error_message, enabled)
SELECT 'PATHOLOGY_CASE','pathology_no','UNIQUE',NULL,'病理号重复',1 WHERE NOT EXISTS
(SELECT 1 FROM validation_rule WHERE business_type='PATHOLOGY_CASE' AND field_name='pathology_no' AND rule_type='UNIQUE');
INSERT INTO validation_rule(business_type, field_name, rule_type, rule_config, error_message, enabled)
SELECT 'VISIT','admission_time','CROSS_FIELD','<=,discharge_time','入院时间不能晚于出院时间',1 WHERE NOT EXISTS
(SELECT 1 FROM validation_rule WHERE business_type='VISIT' AND field_name='admission_time' AND rule_type='CROSS_FIELD');
INSERT INTO validation_rule(business_type, field_name, rule_type, rule_config, error_message, enabled)
SELECT 'PATIENT','gender','CROSS_RECORD','patient_no','患者性别跨来源不一致',1 WHERE NOT EXISTS
(SELECT 1 FROM validation_rule WHERE business_type='PATIENT' AND field_name='gender' AND rule_type='CROSS_RECORD');
INSERT INTO report_template(id, name, report_type, format, sender_type, endpoint, enabled)
SELECT 1, '患者数据 JSON 上报', 'PATIENT', 'JSON', 'HTTP', NULL, 1 WHERE NOT EXISTS (SELECT 1 FROM report_template WHERE id=1);
INSERT IGNORE INTO mock_hospital.mock_his_patient(id, patient_no, patient_name, sex_code, birthday, age, id_card, phone, update_time) VALUES
(1, 'P20260001', '张三', '1', '1981-03-12', 45, '110101198103120011', '13800000001', NOW()),
(2, 'P20260002', '异常示例', '1', '1991-05-20', 235, NULL, '13800000002', NOW());
INSERT IGNORE INTO mock_hospital.mock_his_visit(id, patient_id, visit_no, visit_type, department_name, doctor_name, admission_time, update_time) VALUES
(1, 1, 'V20260001', 'OUTPATIENT', '病理科', '李医生', NOW(), NOW());
INSERT IGNORE INTO mock_hospital.mock_emr_diagnosis(id, patient_id, visit_id, diagnosis_code, diagnosis_name, diagnosis_type, diagnosis_time, update_time) VALUES
(1, 1, 1, 'D12.1', '结肠良性肿瘤', 'PRIMARY', NOW(), NOW());
INSERT IGNORE INTO mock_hospital.mock_lis_result(id, patient_id, visit_id, item_code, item_name, result_value, result_unit, reference_range, abnormal_flag, result_time, update_time) VALUES
(1, 1, 1, 'WBC', '白细胞计数', '6.8', '10^9/L', '3.5-9.5', 'N', NOW(), NOW());
