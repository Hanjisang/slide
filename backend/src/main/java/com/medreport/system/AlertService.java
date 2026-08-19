package com.medreport.system;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AlertService {
    private final JdbcTemplate jdbc;
    private final MonitoringSnapshotService snapshots;
    private final InMemoryCriticalAlertBuffer criticalBuffer;

    public AlertService(JdbcTemplate jdbc, MonitoringSnapshotService snapshots, InMemoryCriticalAlertBuffer criticalBuffer) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.criticalBuffer = criticalBuffer;
    }

    public void emit(String type, String severity, String sourceType, Long sourceId, String message) {
        observe(new Rule(null, type, severity, 0, 0, 1, 1), sourceType, sourceId, true, message, "");
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    @SuppressWarnings("unchecked")
    public void evaluate() {
        snapshots.invalidate();
        Map<String, Object> snapshot = snapshots.snapshot();
        Map<String, Object> components = (Map<String, Object>) snapshot.get("components");
        Map<String, Object> mysql = (Map<String, Object>) components.get("mysql");
        if (!"UP".equals(mysql.get("status"))) return;

        flushRecoveredMysqlIncident();
        Map<String, Rule> rules = loadRules();
        Map<String, Object> resources = (Map<String, Object>) snapshot.get("resources");
        for (MonitorDefinition definition : MonitorDefinition.defaults()) {
            if ("COMPONENT".equals(definition.category())) component(rules, components, definition.monitorCode(), definition.alertType(), definition.displayName());
            if ("RESOURCE".equals(definition.category())) resource(rules, resources, definition.monitorCode(), definition.alertType(), definition.displayName());
        }

        for (Map<String, Object> target : (List<Map<String, Object>>) snapshot.get("storageTargets")) {
            Long id = ((Number) target.get("id")).longValue();
            String name = String.valueOf(target.get("name"));
            observe(rule(rules, "STORAGE_TARGET_DOWN"), "STORAGE_TARGET", id, !"UP".equals(target.get("status")), name + " 存储目标不可用", name + " 存储目标已恢复");
            observe(rule(rules, "STORAGE_READ_FAILED"), "STORAGE_TARGET", id, "FAILED".equals(target.get("readStatus")), name + " 存储读取失败", name + " 存储读取已恢复");
            observe(rule(rules, "STORAGE_WRITE_FAILED"), "STORAGE_TARGET", id, "FAILED".equals(target.get("writeStatus")), name + " 存储写入失败", name + " 存储写入已恢复");
            threshold(rules, "STORAGE_USAGE_HIGH", "STORAGE_TARGET", id, target.get("usagePercent"), name + " 存储使用率");
        }

        Map<String, Object> tasks = (Map<String, Object>) snapshot.get("tasks");
        task(rules, tasks, "collect", "COLLECT_FAILED", "COLLECT_STUCK", "采集");
        task(rules, tasks, "slide", "SLIDE_PARSE_FAILED", "SLIDE_PARSE_STUCK", "切片解析");
        task(rules, tasks, "archive", "ARCHIVE_FAILED", "ARCHIVE_STUCK", "归档");
        task(rules, tasks, "backup", "BACKUP_FAILED", "BACKUP_STUCK", "备份");
        task(rules, tasks, "report", "REPORT_FAILED", "REPORT_STUCK", "上报");

        Map<String, Object> queues = (Map<String, Object>) snapshot.get("queues");
        queue(rules, queues, "pendingCollectTasks", "COLLECT_QUEUE_BACKLOG", "采集");
        queue(rules, queues, "pendingSlides", "SLIDE_QUEUE_BACKLOG", "切片解析");
        queue(rules, queues, "pendingReports", "REPORT_QUEUE_BACKLOG", "上报");
        queue(rules, queues, "pendingArchiveTasks", "ARCHIVE_QUEUE_BACKLOG", "归档");
        queue(rules, queues, "pendingBackupTasks", "BACKUP_QUEUE_BACKLOG", "备份");

        Rule parserRule = rule(rules, "PARSER_FORMAT_FAILED");
        Set<String> failingParsers = new HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT file_format,COUNT(*) failure_count FROM slide_file WHERE status='FAILED' AND updated_at>=DATE_SUB(NOW(),INTERVAL 1 HOUR) GROUP BY file_format")) {
            String format = String.valueOf(row.get("file_format"));
            double failures = ((Number) row.get("failure_count")).doubleValue();
            String source = "PARSER_" + format;
            if (failures >= parserRule.threshold()) failingParsers.add(source);
            observe(parserRule, source, null, failures >= parserRule.threshold(), format + " 最近一小时解析失败 " + (long) failures + " 次", format + " 解析已恢复");
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT source_type FROM alert_event WHERE event_type='PARSER_FORMAT_FAILED' AND active_key IS NOT NULL")) {
            String source = String.valueOf(row.get("source_type"));
            if (!failingParsers.contains(source)) observe(parserRule, source, null, false, "", source.replace("PARSER_", "") + " 解析已恢复");
        }
    }

    @SuppressWarnings("unchecked")
    private void component(Map<String, Rule> rules, Map<String, Object> components, String key, String type, String name) {
        Map<String, Object> value = (Map<String, Object>) components.get(key);
        boolean breached = value == null || !"UP".equals(value.get("status"));
        String status = value == null ? "UNKNOWN" : String.valueOf(value.get("status"));
        observe(rule(rules, type), type.replace("_DOWN", ""), null, breached, name + " 状态异常: " + status, name + " 已恢复");
    }

    @SuppressWarnings("unchecked")
    private void resource(Map<String, Rule> rules, Map<String, Object> resources, String key, String type, String name) {
        Map<String, Object> value = (Map<String, Object>) resources.get(key);
        threshold(rules, type, "SYSTEM", null, value == null ? null : value.get("usagePercent"), name + " 使用率");
    }

    @SuppressWarnings("unchecked")
    private void task(Map<String, Rule> rules, Map<String, Object> tasks, String key, String failedType, String stuckType, String name) {
        Map<String, Object> task = (Map<String, Object>) tasks.get(key);
        if (task == null) return;
        Rule failed = rule(rules, failedType);
        double failedCount = number(task.get("failed"));
        observe(failed, key.toUpperCase(), null, failedCount >= failed.threshold(), name + "最近一小时失败 " + (long) failedCount + " 次", name + "任务已恢复");
        Rule stuck = rule(rules, stuckType);
        double minutes = number(task.get("oldestRunningMinutes"));
        observe(stuck, key.toUpperCase(), null, number(task.get("running")) > 0 && minutes >= stuck.threshold(), name + "任务超过 " + (long) minutes + " 分钟无进度", name + "任务已恢复");
    }

    private void queue(Map<String, Rule> rules, Map<String, Object> queues, String key, String type, String name) {
        Rule rule = rule(rules, type);
        double count = number(queues.get(key));
        observe(rule, key, null, count >= rule.threshold(), name + "队列积压 " + (long) count + " 项", name + "队列积压已恢复");
    }

    private void threshold(Map<String, Rule> rules, String type, String sourceType, Long sourceId, Object rawValue, String name) {
        if (!(rawValue instanceof Number number)) return;
        Rule rule = rule(rules, type);
        double value = number.doubleValue();
        boolean active = hasActive(key(type, sourceType, sourceId));
        boolean breached = active ? value >= rule.recoveryThreshold() : value >= rule.threshold();
        observe(rule, sourceType, sourceId, breached, name + "达到 " + value + "%", name + "恢复到 " + value + "%");
    }

    void observe(Rule rule, String sourceType, Long sourceId, boolean breached, String message, String recoveryMessage) {
        String activeKey = key(rule.type(), sourceType, sourceId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM alert_event WHERE active_key=? LIMIT 1", activeKey);
        Map<String, Object> active = rows.isEmpty() ? null : rows.getFirst();
        if (breached) {
            if (active == null) {
                String status = rule.triggerCount() <= 1 ? "OPEN" : "PENDING";
                try {
                    jdbc.update("""
                            INSERT INTO alert_event(rule_id,event_type,severity,source_type,source_id,message,status,dedup_key,active_key,
                            first_seen_at,last_seen_at,occurrence_count,breach_count,healthy_count)
                            VALUES (?,?,?,?,?,?,?,?,?,NOW(),NOW(),1,1,0)
                            """, rule.id(), rule.type(), rule.severity(), sourceType, sourceId, message, status, activeKey, activeKey);
                } catch (DuplicateKeyException ignored) {
                    updateBreach(activeKey, rule, message);
                }
            } else updateBreach(activeKey, rule, message);
            return;
        }
        if (active == null) return;
        int healthy = ((Number) active.getOrDefault("healthy_count", 0)).intValue() + 1;
        if (healthy >= rule.recoveryCount()) {
            jdbc.update("""
                    UPDATE alert_event SET status='CLOSED',closed_by='SYSTEM',closed_at=NOW(),recovered_at=NOW(),recovery_message=?,
                    last_seen_at=NOW(),healthy_count=?,active_key=NULL WHERE id=?
                    """, recoveryMessage, healthy, active.get("id"));
        } else jdbc.update("UPDATE alert_event SET healthy_count=?,last_seen_at=NOW() WHERE id=?", healthy, active.get("id"));
    }

    private void updateBreach(String activeKey, Rule rule, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,status,breach_count FROM alert_event WHERE active_key=? LIMIT 1", activeKey);
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.getFirst();
        int breaches = ((Number) row.getOrDefault("breach_count", 0)).intValue() + 1;
        String status = "PENDING".equals(row.get("status")) && breaches >= rule.triggerCount() ? "OPEN" : String.valueOf(row.get("status"));
        jdbc.update("UPDATE alert_event SET status=?,severity=?,message=?,last_seen_at=NOW(),occurrence_count=occurrence_count+1,breach_count=?,healthy_count=0 WHERE id=?",
                status, rule.severity(), message, breaches, row.get("id"));
    }

    private boolean hasActive(String activeKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE active_key=?", Integer.class, activeKey);
        return count != null && count > 0;
    }

    private Map<String, Rule> loadRules() {
        Map<String, Rule> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM alert_rule WHERE enabled=1")) {
            String type = String.valueOf(row.get("rule_type"));
            result.put(type, new Rule(((Number) row.get("id")).longValue(), type, String.valueOf(row.get("severity")),
                    number(row.get("threshold_value")), number(row.get("recovery_threshold")),
                    integer(row.get("trigger_count"), 1), integer(row.get("recovery_count"), 1)));
        }
        return result;
    }

    private Rule rule(Map<String, Rule> rules, String type) {
        return rules.getOrDefault(type, new Rule(null, type, "WARNING", 1, 0, 1, 1));
    }

    private void flushRecoveredMysqlIncident() {
        InMemoryCriticalAlertBuffer.Incident incident = criticalBuffer.recoveredIncident();
        if (incident == null) return;
        jdbc.update("""
                INSERT INTO alert_event(event_type,severity,source_type,message,status,dedup_key,first_seen_at,last_seen_at,
                occurrence_count,recovered_at,recovery_message,closed_by,closed_at)
                VALUES ('MYSQL_DOWN','CRITICAL','MYSQL',?,'CLOSED',?,?,?,1,?,'MySQL 数据库已恢复','SYSTEM',?)
                """, incident.message() == null ? "MySQL 数据库不可用" : incident.message(), key("MYSQL_DOWN", "MYSQL", null),
                incident.startedAt(), incident.recoveredAt(), incident.recoveredAt(), incident.recoveredAt());
        criticalBuffer.clear(incident);
    }

    private String key(String type, String sourceType, Long sourceId) { return type + "|" + sourceType + "|" + (sourceId == null ? "_" : sourceId); }
    private double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0; }
    private int integer(Object value, int fallback) { return value instanceof Number number ? number.intValue() : fallback; }

    record Rule(Long id, String type, String severity, double threshold, double recoveryThreshold, int triggerCount, int recoveryCount) {}
}
