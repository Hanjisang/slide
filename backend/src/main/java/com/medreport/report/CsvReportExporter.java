package com.medreport.report;

import com.medreport.report.ReportModels.ExportResult;
import com.medreport.report.ReportModels.ReportContext;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class CsvReportExporter implements ReportExporter {
    private final Path reportDir;
    public CsvReportExporter(@Value("${app.report-dir}") String reportDir) { this.reportDir = Path.of(reportDir); }
    @Override public boolean supports(String format) { return "CSV".equalsIgnoreCase(format); }
    @Override public ExportResult export(ReportContext context) {
        try {
            Files.createDirectories(reportDir); Path path = reportDir.resolve(context.batchNo() + ".csv");
            List<String> headers = context.records().isEmpty() ? List.of("id") : new ArrayList<>(context.records().getFirst().keySet());
            try (CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), CSVFormat.DEFAULT.builder().setHeader(headers.toArray(String[]::new)).get())) {
                for (var record : context.records()) printer.printRecord(headers.stream().map(record::get).toList());
            }
            return new ExportResult(path.toAbsolutePath(), "CSV", Files.size(path));
        } catch (Exception ex) { throw new IllegalStateException("CSV 导出失败", ex); }
    }
}

