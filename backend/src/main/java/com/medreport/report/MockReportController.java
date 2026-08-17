package com.medreport.report;

import com.medreport.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/mock-report")
public class MockReportController {
    private final AtomicBoolean failMode = new AtomicBoolean(false);

    @PostMapping(value = "/receive", consumes = "application/octet-stream")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody byte[] payload, @RequestHeader(value = "X-Report-Batch", required = false) String batch) {
        if (failMode.get()) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("success", false, "message", "mock failure"));
        return ResponseEntity.ok(Map.of("success", true, "batch", batch == null ? "unknown" : batch, "bytes", payload.length));
    }

    @GetMapping("/mode")
    public ApiResponse<Map<String, Object>> mode() { return ApiResponse.ok(Map.of("fail", failMode.get())); }

    @PutMapping("/mode")
    public ApiResponse<Map<String, Object>> mode(@RequestBody Map<String, Object> body) {
        failMode.set(Boolean.TRUE.equals(body.get("fail")));
        return mode();
    }
}
