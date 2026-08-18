package com.medreport.slide.archive;

import com.medreport.slide.file.SlideFileService;
import com.medreport.slide.storage.FileMetadata;
import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTarget;
import com.medreport.slide.storage.StorageTargetService;
import com.medreport.system.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveServiceTest {
    @Test
    void copiesAndVerifiesSizeAndMd5BeforeCompleting(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);SlideFileService files=mock(SlideFileService.class);
        StorageTargetService targets=mock(StorageTargetService.class);StorageProvider storage=mock(StorageProvider.class);
        ArchiveTaskService tasks=mock(ArchiveTaskService.class);AuditService audit=mock(AuditService.class);
        StorageTarget hot=new StorageTarget(1,"hot","S3","e","a","s","hot","","HOT",true);
        StorageTarget archive=new StorageTarget(2,"archive","S3","e","a","s","archive","","ARCHIVE",true);
        when(files.find(7)).thenReturn(Map.of("status","READY","storage_target_id",1L,"object_key","slides/a.svs","md5","abc"));
        when(targets.find(1)).thenReturn(hot);when(targets.find(2)).thenReturn(archive);when(tasks.create(anyLong(),anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(9L);
        when(storage.metadata(hot,"slides/a.svs")).thenReturn(new FileMetadata(3,"abc"));
        when(storage.copy(eq(hot),eq("slides/a.svs"),eq(archive),anyString())).thenReturn(new FileMetadata(3,"abc"));
        when(storage.exists(eq(archive),anyString())).thenReturn(true);when(jdbc.queryForMap(anyString(),eq(9L))).thenReturn(Map.of("id",9L,"status","SUCCESS"));

        Map<String,Object> result=new ArchiveService(jdbc,files,targets,storage,tasks,audit).archive(7,2L,"note","operator");

        assertEquals("SUCCESS",result.get("status"));verify(storage).copy(eq(hot),eq("slides/a.svs"),eq(archive),contains("slides/a.svs"));
        verify(tasks).success(9L,"abc");verify(jdbc).update(contains("archive_status='SUCCESS'"),eq(2L),anyString(),eq("operator"),eq(7L));
        verify(storage,never()).delete(any(),anyString());
    }
}
