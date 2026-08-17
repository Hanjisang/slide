package com.medreport.report;

import com.medreport.report.ReportModels.ReportPackage;
import com.medreport.report.ReportModels.SendResult;

public interface ReportSender {
    boolean supports(String senderType);
    SendResult send(ReportPackage reportPackage);
}

