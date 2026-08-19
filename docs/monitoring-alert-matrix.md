# v0.3.1 监控告警矩阵

统一数据源为 `MonitoringSnapshotService`。系统健康、指标、告警调度和前端均消费同一份快照；告警事件使用 `event_type + source_type + source_id` 去重，默认连续 2 次异常打开、连续 2 次正常自动关闭。

| 监控对象 | 指标 | 异常条件 | 告警 | 等级 | 防抖 | 恢复 | 实测 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MySQL | SELECT 1 | 不可连接 | MYSQL_DOWN | CRITICAL | 2/2 | 自动 CLOSED；数据库不可写时内存缓冲 | 停库实时 CRITICAL，恢复补录 CLOSED |
| MinIO | API/Bucket 只读探测 | DOWN/DEGRADED | MINIO_DOWN | CRITICAL | 2/2 | 自动 CLOSED | 停库 OPEN，恢复 CLOSED |
| Slide Worker | `/health` | DOWN | SLIDE_WORKER_DOWN | CRITICAL | 2/2 | 自动 CLOSED | 停库 OPEN，恢复 CLOSED |
| Go Parser | Worker health | DOWN | GO_PARSER_DOWN | WARNING | 2/2 | 自动 CLOSED | 独立停库 OPEN，恢复 CLOSED |
| CPU | usagePercent | >= alert_rule.threshold | CPU_USAGE_HIGH | WARNING | 2/2 | recovery_threshold | 临时阈值 0 真实触发，已恢复 90/85 |
| Memory | usagePercent | >= alert_rule.threshold | MEMORY_USAGE_HIGH | WARNING | 2/2 | recovery_threshold | 临时阈值 0 真实 OPEN，恢复 CLOSED |
| Disk | usagePercent | >= alert_rule.threshold | DISK_USAGE_HIGH | WARNING | 2/2 | recovery_threshold | 临时阈值 0 真实 OPEN，恢复 CLOSED |
| JVM Heap | usagePercent | >= alert_rule.threshold | JVM_HEAP_USAGE_HIGH | WARNING | 2/2 | recovery_threshold | 临时阈值 0 真实 OPEN，恢复 CLOSED |
| Storage Target | connectivity/read/write | DOWN/read/write failed | STORAGE_TARGET_DOWN / STORAGE_READ_FAILED / STORAGE_WRITE_FAILED | CRITICAL | 2/2 | 自动 CLOSED | id=3 无效 endpoint OPEN，恢复 CLOSED |
| 采集 | failed/running minutes | failed/stuck | COLLECT_FAILED / COLLECT_STUCK | WARNING | rule | 自动 CLOSED | FAILED/STUCK 实测通过 |
| 切片解析 | failed/running minutes | failed/stuck | SLIDE_PARSE_FAILED / SLIDE_PARSE_STUCK | WARNING | rule | 自动 CLOSED | FAILED/STUCK 实测通过 |
| 归档 | failed/running minutes | failed/stuck | ARCHIVE_FAILED / ARCHIVE_STUCK | WARNING | rule | 自动 CLOSED | FAILED/STUCK 实测通过 |
| 备份 | failed/running minutes | failed/stuck | BACKUP_FAILED / BACKUP_STUCK | WARNING | rule | 自动 CLOSED | FAILED/STUCK 实测通过 |
| 上报 | failed/running minutes | failed/stuck | REPORT_FAILED / REPORT_STUCK | WARNING | rule | 自动 CLOSED | FAILED/STUCK 实测通过 |
| 各队列 | pending count | >= alert_rule.threshold | *_QUEUE_BACKLOG | WARNING | rule | recovery_threshold | SQL 可制造 |

## 事件状态

`PENDING -> OPEN -> ACKNOWLEDGED -> CLOSED`。异常事件持续更新 `last_seen_at` 和 `occurrence_count`；恢复由系统写入 `closed_by=SYSTEM`、`recovered_at` 和 `recovery_message`。历史事件保留，活动事件使用唯一 `active_key` 防止并发重复插入。
