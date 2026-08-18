package com.medreport.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medreport.report.ReportModels.ExportResult;
import com.medreport.report.ReportModels.ReportContext;
import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTargetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class PathologyPackageExporter implements ReportExporter {
    private final ObjectMapper mapper;private final Path reportDir;private final StorageProvider storage;private final StorageTargetService targets;
    public PathologyPackageExporter(ObjectMapper mapper,@Value("${app.report-dir}")String reportDir,StorageProvider storage,StorageTargetService targets){
        this.mapper=mapper;this.reportDir=Path.of(reportDir);this.storage=storage;this.targets=targets;}
    @Override public boolean supports(String format){return false;}
    @Override public boolean supports(String format,String reportType){return "ZIP".equalsIgnoreCase(format)&&"PATHOLOGY_PACKAGE".equalsIgnoreCase(reportType);}

    @Override @SuppressWarnings("unchecked") public ExportResult export(ReportContext context){
        try{Files.createDirectories(reportDir);Path path=reportDir.resolve(context.batchNo()+".zip");List<Map<String,Object>> patients=new ArrayList<>(),pathologies=new ArrayList<>(),diagnoses=new ArrayList<>(),visits=new ArrayList<>(),slideManifest=new ArrayList<>();
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))){Set<Long> patientIds=new HashSet<>();
                for(Map<String,Object> record:context.records()){
                    Map<String,Object> patient=(Map<String,Object>)record.get("patient");if(patient!=null&&patient.get("id") instanceof Number n&&patientIds.add(n.longValue()))patients.add(patient);
                    pathologies.add((Map<String,Object>)record.get("pathology"));diagnoses.addAll((List<Map<String,Object>>)record.get("diagnoses"));visits.addAll((List<Map<String,Object>>)record.get("visits"));
                    for(Map<String,Object> slide:(List<Map<String,Object>>)record.get("slides")){String fileName=uniqueSlideName(slide);String entry="slides/"+fileName;
                        zip.putNextEntry(new ZipEntry(entry));long targetId=slide.get("storage_target_id") instanceof Number n?n.longValue():targets.requireClass("HOT").id();try(InputStream input=storage.read(targets.find(targetId),String.valueOf(slide.get("object_key")))){input.transferTo(zip);}zip.closeEntry();
                        Map<String,Object> manifestItem=new LinkedHashMap<>();manifestItem.put("slideId",slide.get("id"));manifestItem.put("file",entry);manifestItem.put("md5",slide.get("md5"));manifestItem.put("size",slide.get("file_size"));manifestItem.put("format",slide.get("file_format"));slideManifest.add(manifestItem);}}
                write(zip,"data/patient.json",patients);write(zip,"data/pathology.json",pathologies);write(zip,"data/diagnosis.json",diagnoses);write(zip,"data/visit.json",visits);
                Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("batchNo",context.batchNo());manifest.put("reportType",context.reportType());manifest.put("generatedAt",LocalDateTime.now().toString());manifest.put("caseCount",context.records().size());manifest.put("slides",slideManifest);write(zip,"manifest.json",manifest);}
            return new ExportResult(path.toAbsolutePath(),"ZIP",Files.size(path));
        }catch(Exception ex){throw new IllegalStateException("病理上报包导出失败: "+ex.getMessage(),ex);}
    }
    private void write(ZipOutputStream zip,String name,Object value)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));zip.closeEntry();}
    private String uniqueSlideName(Map<String,Object> slide){return slide.get("id")+"-"+String.valueOf(slide.get("file_name")).replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]","_");}
}
