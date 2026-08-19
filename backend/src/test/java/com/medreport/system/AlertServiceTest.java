package com.medreport.system;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertServiceTest {
    @Test
    void debounceDeduplicatesAndRecoversOneActiveIncident() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MonitoringSnapshotService snapshots = mock(MonitoringSnapshotService.class);
        InMemoryCriticalAlertBuffer buffer = new InMemoryCriticalAlertBuffer();
        when(jdbc.queryForList(anyString(), anyString())).thenReturn(
                java.util.List.of(),
                java.util.List.of(Map.of("id", 1L, "status", "PENDING", "breach_count", 1, "healthy_count", 0)),
                java.util.List.of(Map.of("id", 1L, "status", "PENDING", "breach_count", 1)),
                java.util.List.of(Map.of("id", 1L, "status", "OPEN", "breach_count", 2, "healthy_count", 0)),
                java.util.List.of(Map.of("id", 1L, "status", "OPEN", "breach_count", 2, "healthy_count", 1)));
        AlertService service = new AlertService(jdbc, snapshots, buffer);
        AlertService.Rule rule = new AlertService.Rule(1L, "MINIO_DOWN", "CRITICAL", 1, 0, 2, 2);
        service.observe(rule, "MINIO", null, true, "MinIO down", "MinIO recovered");
        service.observe(rule, "MINIO", null, true, "MinIO down", "MinIO recovered");
        service.observe(rule, "MINIO", null, false, "", "MinIO recovered");
        service.observe(rule, "MINIO", null, false, "", "MinIO recovered");
        verify(jdbc, times(1)).update(startsWith("INSERT INTO alert_event"), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(jdbc, times(1)).update(contains("active_key=NULL"), any(), any(), any());
    }

    @Test
    void mysqlBufferTracksFailureAndRecoveryWithoutDatabase() {
        InMemoryCriticalAlertBuffer buffer = new InMemoryCriticalAlertBuffer();
        buffer.observe(false, "database unavailable");
        Map<String, Object> active = buffer.activeAlert();
        assertEquals("MYSQL_DOWN", active.get("eventType"));
        buffer.observe(true, "");
        assertNotNull(buffer.recoveredIncident());
    }
}
