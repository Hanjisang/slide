package com.medreport.backup;

import com.medreport.file.FileAssetService;
import com.medreport.slide.storage.FileMetadata;
import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTarget;
import com.medreport.slide.storage.StorageTargetService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackupServiceTest {
    @Test
    void preservesMd5WhenCopyingToBackup(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);FileAssetService files=mock(FileAssetService.class);
        StorageTargetService targets=mock(StorageTargetService.class);StorageProvider storage=mock(StorageProvider.class);
        StorageTarget hot=new StorageTarget(1,"hot","S3","e","a","s","hot","","HOT",true);
        StorageTarget backup=new StorageTarget(3,"backup","S3","e","a","s","backup","","BACKUP",true);
        when(files.find(5)).thenReturn(Map.of("storage_target_id",1L,"object_key","files/a","md5","abc","current_version",1,"file_name","a.svs"));
        when(targets.find(1)).thenReturn(hot);when(targets.find(3)).thenReturn(backup);
        when(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class)).thenReturn(11L);
        when(storage.copy(eq(hot),eq("files/a"),eq(backup),anyString())).thenReturn(new FileMetadata(10,"abc"));
        when(jdbc.queryForMap(anyString(),eq(11L))).thenReturn(Map.of("id",11L,"status","SUCCESS"));

        Map<String,Object> result=new BackupService(jdbc,files,targets,storage).backup(5,3L);

        assertEquals("SUCCESS",result.get("status"));verify(storage).copy(eq(hot),eq("files/a"),eq(backup),contains("file-5"));
        verify(jdbc).update(contains("status='SUCCESS'"),eq("abc"),eq(11L));
    }
}
