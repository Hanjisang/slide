package com.medreport.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medreport.report.ReportModels.ExportResult;
import com.medreport.report.ReportModels.ReportContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class ZipReportExporter implements ReportExporter {
    private final ObjectMapper mapper;
    private final Path reportDir;
    public ZipReportExporter(ObjectMapper mapper, @Value("${app.report-dir}") String reportDir) { this.mapper = mapper; this.reportDir = Path.of(reportDir); }
    @Override public boolean supports(String format) { return "ZIP".equalsIgnoreCase(format); }
    @Override public boolean supports(String format,String reportType){return supports(format)&&!"PATHOLOGY_PACKAGE".equalsIgnoreCase(reportType);}
    @Override public ExportResult export(ReportContext context) {
        try {
            Files.createDirectories(reportDir); Path path = reportDir.resolve(context.batchNo() + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
                zip.putNextEntry(new ZipEntry(context.batchNo() + ".json"));
                zip.write(mapper.writeValueAsBytes(Map.of("batchNo", context.batchNo(), "reportType", context.reportType(), "records", context.records())));
                zip.closeEntry();
            }
            return new ExportResult(path.toAbsolutePath(), "ZIP", Files.size(path));
        } catch (Exception ex) { throw new IllegalStateException("ZIP 导出失败", ex); }
    }
}
