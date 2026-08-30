package com.medreport.slide.file;

import com.medreport.common.BizException;
import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTargetService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlideFileServiceTest {
    @Test
    void deleteIsLogicalAndRecordsOperator(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);when(jdbc.update(anyString(),any(),anyLong())).thenReturn(1);
        new SlideFileService(jdbc,mock(StorageTargetService.class),mock(StorageProvider.class)).softDelete(4,"viewer");
        verify(jdbc).update(contains("deleted=1"),eq("viewer"),eq(4L));
    }

    @Test
    void metadataOnlySlideCannotBeDownloaded(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(9L))).thenReturn(List.of(Map.of("id",9L,"status","METADATA_ONLY")));
        SlideFileService service=new SlideFileService(jdbc,mock(StorageTargetService.class),mock(StorageProvider.class));
        assertThrows(BizException.class,()->service.download(9));
    }
}
