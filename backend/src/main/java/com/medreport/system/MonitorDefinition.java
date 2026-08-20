package com.medreport.system;

import java.util.List;

public record MonitorDefinition(
        String monitorCode,
        String displayName,
        String category,
        boolean alertable,
        String alertType,
        String severity,
        double threshold,
        double recoveryThreshold,
        int failureCount,
        int recoveryCount
) {
    public static MonitorDefinition component(String code, String name, String alertType, String severity) {
        return new MonitorDefinition(code, name, "COMPONENT", true, alertType, severity, 1, 0, 2, 2);
    }

    public static MonitorDefinition resource(String code, String name, String alertType, double threshold, double recoveryThreshold) {
        return new MonitorDefinition(code, name, "RESOURCE", true, alertType, "WARNING", threshold, recoveryThreshold, 2, 2);
    }

    public static List<MonitorDefinition> defaults() {
        return List.of(
                component("mysql", "MySQL 数据库", "MYSQL_DOWN", "CRITICAL"),
                component("minio", "MinIO 对象存储", "MINIO_DOWN", "CRITICAL"),
                component("slideWorker", "Slide Worker", "SLIDE_WORKER_DOWN", "CRITICAL"),
                component("goParser", "Go Parser", "GO_PARSER_DOWN", "WARNING"),
                resource("cpu", "CPU", "CPU_USAGE_HIGH", 90, 85),
                resource("memory", "内存", "MEMORY_USAGE_HIGH", 90, 85),
                resource("disk", "磁盘", "DISK_USAGE_HIGH", 85, 80),
                resource("jvmHeap", "JVM Heap", "JVM_HEAP_USAGE_HIGH", 90, 85)
        );
    }
}
