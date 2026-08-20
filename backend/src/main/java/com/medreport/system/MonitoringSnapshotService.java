package com.medreport.system;

import com.medreport.slide.storage.S3StorageProvider;
import com.medreport.slide.storage.StorageTargetService;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MonitoringSnapshotService {
    private static final long CACHE_MILLIS = 5_000;
    private final JdbcTemplate jdbc;
    private final StorageTargetService targets;
    private final S3StorageProvider storage;
    private final MinioClient minio;
    private final RestClient worker;
    private final InMemoryCriticalAlertBuffer criticalBuffer;
    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public MonitoringSnapshotService(JdbcTemplate jdbc, StorageTargetService targets, S3StorageProvider storage, MinioClient minio,
                                     RestClient.Builder builder, InMemoryCriticalAlertBuffer criticalBuffer,
                                     @Value("${app.slide-worker-url}") String workerUrl) {
        this.jdbc = jdbc;
        this.targets = targets;
        this.storage = storage;
        this.minio = minio;
        this.worker = builder.baseUrl(workerUrl).build();
        this.criticalBuffer = criticalBuffer;
    }

    public Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        Map<String, Object> current = cached;
        if (current != null && now - cachedAt < CACHE_MILLIS) return current;
        synchronized (this) {
            if (cached != null && now - cachedAt < CACHE_MILLIS) return cached;
            cached = capture();
            cachedAt = now;
            return cached;
        }
    }

    public synchronized void invalidate() { cachedAt = 0; }

    private Map<String, Object> capture() {
        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> mysql = mysqlHealth();
        boolean mysqlUp = "UP".equals(mysql.get("status"));
        criticalBuffer.observe(mysqlUp, "MySQL 数据库不可用");
        components.put("mysql", mysql);

        Map<String, Object> workerHealth = workerHealth();
        components.put("slideWorker", workerHealth);
        components.put("goParser", goParserHealth(workerHealth));

        List<Map<String, Object>> storageTargets = mysqlUp ? storageTargets() : List.of();
        components.put("minio", minioHealth(storageTargets));

        Map<String, Object> resources = resources();
        Map<String, Object> queues = mysqlUp ? queues() : unavailableQueues();
        Map<String, Object> tasks = mysqlUp ? tasks() : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capturedAt", Instant.now());
        result.put("components", components);
        result.put("resources", resources);
        result.put("storageTargets", storageTargets);
        result.put("tasks", tasks);
        result.put("queues", queues);
        result.put("parserCapabilities", parserCapabilities(workerHealth));
        Map<String, Object> critical = criticalBuffer.activeAlert();
        result.put("criticalAlerts", critical.isEmpty() ? List.of() : List.of(critical));
        return result;
    }

    private Map<String, Object> mysqlHealth() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return Map.of("status", "UP");
        } catch (Exception ex) {
            return Map.of("status", "DOWN", "message", safeMessage(ex));
        }
    }

    private Map<String, Object> workerHealth() {
        try {
            Map<?, ?> body = worker.get().uri("/health").retrieve().body(Map.class);
            return Map.of("status", "UP", "detail", body == null ? Map.of() : body);
        } catch (Exception ex) {
            return Map.of("status", "DOWN", "message", safeMessage(ex));
        }
    }

    private Map<String, Object> goParserHealth(Map<String, Object> workerHealth) {
        Object detail = workerHealth.get("detail");
        if (detail instanceof Map<?, ?> workerDetail && workerDetail.get("goParser") instanceof Map<?, ?> parser) {
            Map<String, Object> result = new LinkedHashMap<>();
            parser.forEach((key, value) -> result.put(String.valueOf(key), value));
            result.putIfAbsent("status", "DOWN");
            return result;
        }
        return Map.of("status", "DOWN", "message", "Slide Worker 未返回 Go Parser 状态");
    }

    private Object parserCapabilities(Map<String, Object> workerHealth) {
        Object detail = workerHealth.get("detail");
        if (!(detail instanceof Map<?, ?>)) return List.of();
        try {
            List<?> adapters = worker.get().uri("/api/adapters").retrieve().body(List.class);
            return adapters == null ? List.of() : adapters;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<Map<String, Object>> storageTargets() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : targets.listSafe()) {
            if (!enabled(row.get("enabled"))) continue;
            Map<String, Object> item = new LinkedHashMap<>(row);
            try {
                item.putAll(storage.probe(targets.find(((Number) row.get("id")).longValue())));
                item.put("status", "UP");
            } catch (Exception ex) {
                item.put("status", "DOWN");
                item.put("readStatus", "FAILED");
                item.put("writeStatus", "UNKNOWN");
                item.put("message", safeMessage(ex));
            }
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> aggregateMinio(List<Map<String, Object>> storageTargets) {
        if (storageTargets.isEmpty()) return Map.of("status", "DOWN", "message", "没有可用的存储目标");
        long down = storageTargets.stream().filter(row -> !"UP".equals(row.get("status"))).count();
        String status = down == 0 ? "UP" : down == storageTargets.size() ? "DOWN" : "DEGRADED";
        return Map.of("status", status, "targetCount", storageTargets.size(), "unavailableTargets", down);
    }

    private Map<String, Object> minioHealth(List<Map<String, Object>> storageTargets) {
        try {
            var buckets = minio.listBuckets();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "UP");
            result.put("bucketCount", buckets.size());
            result.put("targetCount", storageTargets.size());
            return result;
        } catch (Exception ex) {
            Map<String, Object> result = new LinkedHashMap<>(aggregateMinio(storageTargets));
            result.put("status", "DOWN");
            result.put("message", safeMessage(ex));
            return result;
        }
    }

    private Map<String, Object> resources() {
        Map<String, Object> result = new LinkedHashMap<>();
        com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double cpu = os.getCpuLoad();
        result.put("cpu", Map.of("usagePercent", cpu < 0 ? "UNKNOWN" : round(cpu * 100), "processors", os.getAvailableProcessors()));
        long totalMemory = os.getTotalMemorySize(), freeMemory = os.getFreeMemorySize();
        result.put("memory", Map.of("totalBytes", totalMemory, "usedBytes", totalMemory - freeMemory, "usagePercent", percent(totalMemory - freeMemory, totalMemory)));
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        result.put("jvmHeap", Map.of("usedBytes", heap.getUsed(), "committedBytes", heap.getCommitted(), "maxBytes", heap.getMax(), "usagePercent", percent(heap.getUsed(), heap.getMax())));
        long total = 0, usable = 0;
        for (FileStore store : FileSystems.getDefault().getFileStores()) try { total += store.getTotalSpace(); usable += store.getUsableSpace(); } catch (Exception ignored) {}
        result.put("disk", Map.of("totalBytes", total, "usedBytes", Math.max(0, total - usable), "availableBytes", usable, "usagePercent", percent(total - usable, total)));
        return result;
    }

    private Map<String, Object> queues() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pendingCollectTasks", count("SELECT COUNT(*) FROM collect_task WHERE enabled=1 AND (next_run_time IS NULL OR next_run_time<=NOW())"));
        result.put("failedCollectTasks", count("SELECT COUNT(*) FROM collect_log WHERE status='FAILED' AND created_at>=CURRENT_DATE"));
        result.put("pendingSlides", count("SELECT COUNT(*) FROM slide_file WHERE status IN ('UPLOADING','UPLOADED','PARSING')"));
        result.put("failedSlides", count("SELECT COUNT(*) FROM slide_file WHERE status='FAILED'"));
        result.put("pendingReports", count("SELECT COUNT(*) FROM report_batch WHERE status IN ('PENDING','GENERATING','READY','REPORTING')"));
        result.put("failedReports", count("SELECT COUNT(*) FROM report_batch WHERE status='FAILED'"));
        result.put("pendingArchiveTasks", count("SELECT COUNT(*) FROM archive_task WHERE status IN ('PENDING','COPYING','VERIFYING')"));
        result.put("failedArchiveTasks", count("SELECT COUNT(*) FROM archive_task WHERE status='FAILED'"));
        result.put("pendingBackupTasks", count("SELECT COUNT(*) FROM backup_task WHERE status IN ('PENDING','COPYING','VERIFYING')"));
        result.put("failedBackupTasks", count("SELECT COUNT(*) FROM backup_task WHERE status='FAILED'"));
        return result;
    }

    private Map<String, Object> unavailableQueues() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("pendingCollectTasks", "failedCollectTasks", "pendingSlides", "failedSlides", "pendingReports", "failedReports", "pendingArchiveTasks", "failedArchiveTasks", "pendingBackupTasks", "failedBackupTasks")) result.put(key, "UNKNOWN");
        return result;
    }

    private Map<String, Object> tasks() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collect", task("collect_log", "created_at", List.of("RUNNING")));
        result.put("slide", task("slide_file", "updated_at", List.of("UPLOADING", "UPLOADED", "PARSING")));
        result.put("archive", task("archive_task", "updated_at", List.of("PENDING", "COPYING", "VERIFYING")));
        result.put("backup", task("backup_task", "updated_at", List.of("PENDING", "COPYING", "VERIFYING")));
        result.put("report", task("report_batch", "updated_at", List.of("PENDING", "GENERATING", "READY", "REPORTING")));
        return result;
    }

    private Map<String, Object> task(String table, String timeColumn, List<String> runningStatuses) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("failed", count("SELECT COUNT(*) FROM " + table + " WHERE status='FAILED' AND " + timeColumn + ">=DATE_SUB(NOW(),INTERVAL 1 HOUR)"));
        item.put("running", count("SELECT COUNT(*) FROM " + table + " WHERE status IN (" + quoted(runningStatuses) + ")"));
        Number oldest = jdbc.queryForObject("SELECT COALESCE(MAX(TIMESTAMPDIFF(MINUTE," + timeColumn + ",NOW())),0) FROM " + table + " WHERE status IN (" + quoted(runningStatuses) + ")", Number.class);
        item.put("oldestRunningMinutes", oldest == null ? 0 : oldest.longValue());
        return item;
    }

    private long count(String sql) { Number value = jdbc.queryForObject(sql, Number.class); return value == null ? 0 : value.longValue(); }
    private String quoted(List<String> values) { return values.stream().map(value -> "'" + value + "'").reduce((a, b) -> a + "," + b).orElse("''"); }
    private boolean enabled(Object value) { return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue() != 0 : Boolean.parseBoolean(String.valueOf(value)); }
    private double percent(long used, long total) { return total <= 0 ? 0 : round(used * 100d / total); }
    private double round(double value) { return Math.round(value * 100d) / 100d; }
    private String safeMessage(Exception ex) { return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
}
