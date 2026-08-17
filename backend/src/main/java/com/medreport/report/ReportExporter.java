package com.medreport.report;

import com.medreport.report.ReportModels.ExportResult;
import com.medreport.report.ReportModels.ReportContext;

public interface ReportExporter {
    boolean supports(String format);
    ExportResult export(ReportContext context);
}

