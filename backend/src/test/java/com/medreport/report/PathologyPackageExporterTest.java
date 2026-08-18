package com.medreport.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medreport.report.ReportModels.ReportContext;
import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTarget;
import com.medreport.slide.storage.StorageTargetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PathologyPackageExporterTest {
    @TempDir Path tempDir;

    @Test
    void writesManifestDataAndActualSlideBytes() throws Exception {
        StorageProvider storage=mock(StorageProvider.class);StorageTargetService targets=mock(StorageTargetService.class);
        StorageTarget hot=new StorageTarget(1,"hot","S3","e","a","s","hot","","HOT",true);
        when(targets.find(1)).thenReturn(hot);when(storage.read(hot,"slides/a.svs")).thenReturn(new ByteArrayInputStream("SVS-DATA".getBytes(StandardCharsets.UTF_8)));
        Map<String,Object> slide=Map.of("id",8L,"storage_target_id",1L,"object_key","slides/a.svs","file_name","a.svs","md5","abc","file_size",8L,"file_format","SVS");
        Map<String,Object> record=Map.of("id",7L,"patient",Map.of("id",1L,"name","张三"),"pathology",Map.of("id",7L,"pathology_no","P1"),
                "diagnoses",List.of(Map.of("id",2L)),"visits",List.of(Map.of("id",3L)),"slides",List.of(slide));

        var result=new PathologyPackageExporter(new ObjectMapper(),tempDir.toString(),storage,targets)
                .export(new ReportContext("REPORT_TEST","PATHOLOGY_PACKAGE",List.of(record)));

        try(ZipFile zip=new ZipFile(result.path().toFile())){
            for(String entry:List.of("manifest.json","data/patient.json","data/pathology.json","data/diagnosis.json","data/visit.json","slides/8-a.svs"))assertNotNull(zip.getEntry(entry),entry);
            assertEquals("SVS-DATA",new String(zip.getInputStream(zip.getEntry("slides/8-a.svs")).readAllBytes(),StandardCharsets.UTF_8));
        }
    }
}
