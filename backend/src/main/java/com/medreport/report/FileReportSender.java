package com.medreport.report;

import com.medreport.report.ReportModels.ReportPackage;
import com.medreport.report.ReportModels.SendResult;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class FileReportSender implements ReportSender {
    @Override public boolean supports(String senderType) { return "FILE".equalsIgnoreCase(senderType); }
    @Override public SendResult send(ReportPackage reportPackage) {
        try {
            Path outbox = reportPackage.endpoint() == null || reportPackage.endpoint().isBlank() ? reportPackage.path().getParent().resolve("outbox") : Path.of(reportPackage.endpoint());
            Files.createDirectories(outbox);
            Files.copy(reportPackage.path(), outbox.resolve(reportPackage.path().getFileName()), StandardCopyOption.REPLACE_EXISTING);
            return new SendResult(true, outbox.toAbsolutePath().toString());
        } catch (Exception ex) { return new SendResult(false, ex.getMessage()); }
    }
}
