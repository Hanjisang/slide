package com.medreport.system;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class InMemoryCriticalAlertBuffer {
    private LocalDateTime startedAt;
    private LocalDateTime recoveredAt;
    private String lastMessage;

    public synchronized void observe(boolean available, String message) {
        if (!available) {
            if (startedAt == null) startedAt = LocalDateTime.now();
            recoveredAt = null;
            lastMessage = message;
        } else if (startedAt != null && recoveredAt == null) {
            recoveredAt = LocalDateTime.now();
        }
    }

    public synchronized Map<String, Object> activeAlert() {
        if (startedAt == null || recoveredAt != null) return Map.of();
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("eventType", "MYSQL_DOWN");
        alert.put("severity", "CRITICAL");
        alert.put("sourceType", "MYSQL");
        alert.put("message", lastMessage == null ? "MySQL 数据库不可用" : lastMessage);
        alert.put("startedAt", startedAt);
        return alert;
    }

    public synchronized Incident recoveredIncident() {
        return startedAt != null && recoveredAt != null ? new Incident(startedAt, recoveredAt, lastMessage) : null;
    }

    public synchronized void clear(Incident incident) {
        if (incident != null && incident.startedAt().equals(startedAt) && incident.recoveredAt().equals(recoveredAt)) {
            startedAt = null;
            recoveredAt = null;
            lastMessage = null;
        }
    }

    public record Incident(LocalDateTime startedAt, LocalDateTime recoveredAt, String message) {}
}
