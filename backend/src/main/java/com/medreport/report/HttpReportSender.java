package com.medreport.report;

import com.medreport.report.ReportModels.ReportPackage;
import com.medreport.report.ReportModels.SendResult;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpReportSender implements ReportSender {
    private final RestClient client;
    public HttpReportSender(RestClient.Builder builder) { this.client = builder.build(); }
    @Override public boolean supports(String senderType) { return "HTTP".equalsIgnoreCase(senderType); }
    @Override public SendResult send(ReportPackage reportPackage) {
        try {
            String response = client.post().uri(reportPackage.endpoint()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("X-Report-Batch", String.valueOf(reportPackage.batchId()))
                    .body(new FileSystemResource(reportPackage.path())).retrieve().body(String.class);
            return new SendResult(true, response == null ? "success" : response);
        } catch (Exception ex) { return new SendResult(false, ex.getMessage()); }
    }
}

