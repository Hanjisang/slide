package com.medreport.report;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/report-batches")
public class ReportController {
    private final JdbcTemplate jdbc;
    private final ReportService service;

    public ReportController(JdbcTemplate jdbc, ReportService service) { this.jdbc = jdbc; this.service = service; }

    @GetMapping
    @RequirePermission({"DATA_VIEW","REPORT_GENERATE"})
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM report_batch ORDER BY id DESC LIMIT 300"));
    }

    @GetMapping("/{id}/records")
    @RequirePermission({"DATA_VIEW","REPORT_GENERATE"})
    public ApiResponse<List<Map<String, Object>>> records(@PathVariable long id) {
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM report_record WHERE batch_id=? ORDER BY id", id));
    }

    @PostMapping
    @RequirePermission("REPORT_GENERATE")
    public ApiResponse<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        long id=service.generate(String.valueOf(body.getOrDefault("reportType","PATIENT")),String.valueOf(body.getOrDefault("format","JSON")),
                String.valueOf(body.getOrDefault("senderType","HTTP")),body.get("endpoint")==null?null:String.valueOf(body.get("endpoint")),
                ids(body.get("caseIds")),body.get("templateId") instanceof Number n?n.longValue():null);
        return ApiResponse.ok(Map.of("id", id, "batch", service.find(id)));
    }

    @GetMapping("/pending-cases")
    @RequirePermission({"DATA_VIEW","REPORT_GENERATE"})
    public ApiResponse<List<Map<String,Object>>> pendingCases(){return ApiResponse.ok(service.pendingCases());}

    @PostMapping("/{id}/send")
    @RequirePermission("REPORT_SEND")
    public ApiResponse<ReportModels.SendResult> send(@PathVariable long id) { return ApiResponse.ok(service.send(id, true)); }

    @GetMapping("/{id}/download")
    @RequirePermission({"DATA_VIEW","REPORT_GENERATE"})
    public ResponseEntity<FileSystemResource> download(@PathVariable long id) {
        Map<String, Object> batch = service.find(id);
        Path path = Path.of(String.valueOf(batch.get("file_path")));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(path.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(path));
    }

    private List<Long> ids(Object value){if(!(value instanceof List<?> list))return List.of();
        return list.stream().filter(Number.class::isInstance).map(Number.class::cast).map(Number::longValue).toList();}
}
