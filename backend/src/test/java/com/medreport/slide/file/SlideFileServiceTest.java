package com.medreport.slide.file;

import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTargetService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SlideFileServiceTest {
    @Test
    void deleteIsLogicalAndRecordsOperator(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);when(jdbc.update(anyString(),any(),anyLong())).thenReturn(1);
        new SlideFileService(jdbc,mock(StorageTargetService.class),mock(StorageProvider.class)).softDelete(4,"viewer");
        verify(jdbc).update(contains("deleted=1"),eq("viewer"),eq(4L));
    }
}
