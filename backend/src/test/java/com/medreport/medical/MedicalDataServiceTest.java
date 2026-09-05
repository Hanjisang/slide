package com.medreport.medical;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MedicalDataServiceTest {
    @Test
    void upsertUsesSourceIdentityAndUpdatesExistingRecord() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("SELECT id FROM patient"), eq(Long.class), eq("HIS"), eq("P-001")))
                .thenReturn(42L);

        long id = new MedicalDataService(jdbc).upsert("PATIENT", Map.of(
                "sourceSystem", "HIS", "sourceId", "P-001", "name", "张三", "gender", "M"));

        assertTrue(id == 42L);
        verify(jdbc).update(contains("ON DUPLICATE KEY UPDATE"), any(Object[].class));
    }
}
