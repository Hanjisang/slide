package com.medreport.governance;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BasicDataControllerTest {
    @Test
    void importsCsvExportedWithUtf8Bom() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(Long.class),eq("SPECIMEN_TYPE"))).thenReturn(List.of(1L));
        String csv="\uFEFFsourceValue,targetValue,description,enabled\r\nTISSUE,TISSUE,组织标本,true\r\n";

        Map<String,Object> result=new BasicDataController(jdbc).importData("SPECIMEN_TYPE","CSV",
                new MockMultipartFile("file","data.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8))).data();

        assertEquals(1,result.get("successCount"));assertEquals(0,result.get("errorCount"));
        verify(jdbc).update(contains("ON DUPLICATE KEY UPDATE"),eq(1L),eq("TISSUE"),eq("TISSUE"),eq("组织标本"),eq(true));
    }
}
