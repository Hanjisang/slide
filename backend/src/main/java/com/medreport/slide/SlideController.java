package com.medreport.slide;

import com.medreport.common.ApiResponse;
import com.medreport.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/slides")
public class SlideController {
    private final JdbcTemplate jdbc;
    private final SlideStorageService storage;
    private final AuditService audit;

    public SlideController(JdbcTemplate jdbc, SlideStorageService storage, AuditService audit) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(jdbc.queryForList("""
                SELECT s.*,c.pathology_no,p.name patient_name FROM slide_file s JOIN pathology_case c ON c.id=s.case_id
                LEFT JOIN patient p ON p.id=c.patient_id ORDER BY s.id DESC
                """));
    }

    @GetMapping("/cases")
    public ApiResponse<List<Map<String, Object>>> cases() {
        return ApiResponse.ok(jdbc.queryForList("SELECT c.*,p.name patient_name FROM pathology_case c LEFT JOIN patient p ON p.id=c.patient_id ORDER BY c.id DESC"));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> upload(@RequestParam long caseId, @RequestParam String slideNo, @RequestPart MultipartFile file) {
        long id = storage.upload(caseId, slideNo, file);
        return ApiResponse.ok(Map.of("id", id, "slide", storage.find(id)));
    }

    @PostMapping("/{id}/analyze")
    public ApiResponse<Map<String, Object>> analyze(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> result = storage.analyze(id);
        audit.log(request, "重新解析切片", "数字切片", id, "SUCCESS", String.valueOf(result.get("status")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<Void> archive(@PathVariable long id, HttpServletRequest request) {
        jdbc.update("UPDATE slide_file SET status='ARCHIVED' WHERE id=? AND status='READY'", id);
        audit.log(request, "归档切片", "数字切片", id, "SUCCESS", null);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(storage.find(id));
    }

    @GetMapping("/{id}/tiles/{level}/{x}/{y}")
    public ResponseEntity<byte[]> tile(@PathVariable long id, @PathVariable int level, @PathVariable int x, @PathVariable int y) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.maxAge(Duration.ofDays(1))).body(storage.tile(id, level, x, y));
    }

    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable long id) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.maxAge(Duration.ofDays(1))).body(storage.thumbnail(id));
    }
}
