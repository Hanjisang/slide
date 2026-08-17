# 数据库说明

应用 schema 为 `medical_report`，演示源 schema 为 `mock_hospital`。启动时 `deploy/mysql/00-init.sql` 创建 schema 和授权，Spring Boot 的 `schema.sql` 幂等创建表和基础数据。

## 核心表组

- 账户与运维：`sys_user`、`system_config`、`operation_log`
- 采集：`data_source_config`、`collect_task`、`collect_log`
- 标准医疗数据：`patient`、`visit`、`diagnosis`、`lab_result`、`exam_result`、`medical_operation`、`medication`
- 治理：`mapping_template`、`mapping_field`、`dictionary`、`dictionary_item`
- 质量：`validation_rule`、`validation_error`、`validation_handle_log`
- 病理：`pathology_case`、`slide_file`
- 上报：`report_template`、`report_batch`、`report_record`

患者等来源数据使用 `(source_system, source_id)` 唯一键实现幂等去重。切片状态为 `UPLOADING/UPLOADED/PARSING/READY/FAILED/ARCHIVED`，上报状态为 `PENDING/GENERATING/READY/REPORTING/SUCCESS/FAILED`。

Mock 表：`mock_his_patient`、`mock_his_visit`、`mock_emr_diagnosis`、`mock_lis_result`。

