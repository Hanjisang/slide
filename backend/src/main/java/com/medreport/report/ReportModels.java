package com.medreport.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ReportModels {
    private ReportModels() {}
    public record ReportContext(String batchNo, String reportType, List<Map<String, Object>> records, Map<String,Object> options) {
        public ReportContext(String batchNo,String reportType,List<Map<String,Object>> records){this(batchNo,reportType,records,Map.of());}
    }
    public record ExportResult(Path path, String format, long size) {}
    public record ReportPackage(long batchId, Path path, String endpoint, String senderType) {}
    public record SendResult(boolean success, String message) {}
}
