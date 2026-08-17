package com.medreport.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medreport.report.ReportModels.ExportResult;
import com.medreport.report.ReportModels.ReportContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class JsonReportExporter implements ReportExporter {
    private final ObjectMapper mapper;
    private final Path reportDir;

    public JsonReportExporter(ObjectMapper mapper, @Value("${app.report-dir}") String reportDir) {
        this.mapper = mapper;
        this.reportDir = Path.of(reportDir);
    }

    @Override public boolean supports(String format) { return "JSON".equalsIgnoreCase(format); }

    @Override
    public ExportResult export(ReportContext context) {
        try {
            Files.createDirectories(reportDir);
            Path path = reportDir.resolve(context.batchNo() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), Map.of("batchNo", context.batchNo(), "reportType", context.reportType(), "records", context.records()));
            return new ExportResult(path.toAbsolutePath(), "JSON", Files.size(path));
        } catch (Exception ex) { throw new IllegalStateException("JSON 导出失败", ex); }
    }
}

