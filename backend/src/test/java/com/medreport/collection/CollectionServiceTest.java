package com.medreport.collection;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionServiceTest {
    @Test
    void acceptsMysqlLocalDateTimeWatermarks() {
        LocalDateTime expected = LocalDateTime.of(2026, 8, 29, 23, 59, 59);

        assertEquals(expected, CollectionService.asLocalDateTime(expected, null));
        assertEquals(expected, CollectionService.asLocalDateTime(Timestamp.valueOf(expected), null));
        assertEquals(expected, CollectionService.asLocalDateTime("2026-08-29 23:59:59", null));
    }
}
