package com.medreport.report;

import com.medreport.report.ReportModels.ExportResult;
import com.medreport.report.ReportModels.ReportContext;

public interface ReportExporter {
    boolean supports(String format);
    default boolean supports(String format,String reportType){return supports(format);}
    ExportResult export(ReportContext context);
}
