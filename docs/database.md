# 数据库说明

应用 schema 为 `medical_report`，演示源 schema 为 `mock_hospital`。启动时 `deploy/mysql/00-init.sql` 创建 schema 和授权，Spring Boot 的 `schema.sql` 幂等创建表和基础数据。

## 核心表组

- 账户与运维：`sys_user`、`sys_role`、`sys_permission`、`sys_role_permission`、`system_config`、`operation_log`
- 采集：`data_source_config`、`collect_task`、`collect_log`
- 标准医疗数据：`patient`、`visit`、`diagnosis`、`lab_result`、`exam_result`、`medical_operation`、`medication`
- 治理：`mapping_template`、`mapping_field`、`dictionary`、`dictionary_item`
- 质量：`validation_rule`、`validation_error`、`validation_handle_log`
- 病理与存储：`pathology_case`、`slide_file`、`storage_target`、`archive_task`、`archive_policy`
- 文件与备份：`file_asset`、`file_version`、`backup_policy`、`backup_task`
- 上报：`report_template`、`report_plan`、`report_batch`、`report_record`
- 告警：`alert_rule`、`alert_event`

患者等来源数据使用 `(source_system, source_id)` 唯一键实现幂等去重。切片状态为 `UPLOADING/UPLOADED/PARSING/READY/FAILED/ARCHIVED`，上报状态为 `PENDING/GENERATING/READY/REPORTING/SUCCESS/FAILED`。

Mock 表：`mock_his_patient`、`mock_his_visit`、`mock_emr_diagnosis`、`mock_lis_result`。

## v0.1 到 v0.2 升级

`schema.sql` 负责新安装；`DatabaseUpgradeService` 在启动时查询 `information_schema`，仅补充缺失字段，随后由幂等 seed 初始化角色、权限、字典和默认存储目标。参考 SQL 位于 `deploy/mysql/migrations/V002__upgrade.sql`。

升级时不得删除 `mysql_data`、`minio_data`。历史 `slide_file.bucket_name/object_key/md5` 保留；缺少 `storage_target_id` 的记录映射到默认 HOT 目标。
