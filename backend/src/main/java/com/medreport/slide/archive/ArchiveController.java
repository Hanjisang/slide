package com.medreport.slide.archive;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/archive")
@RequirePermission("SLIDE_ARCHIVE")
public class ArchiveController {
    private final ArchiveTaskService tasks;
    private final JdbcTemplate jdbc;

    public ArchiveController(ArchiveTaskService tasks, JdbcTemplate jdbc) { this.tasks=tasks; this.jdbc=jdbc; }

    @GetMapping("/tasks")
    public ApiResponse<List<Map<String, Object>>> tasks() { return ApiResponse.ok(tasks.list()); }

    @GetMapping("/policies")
    public ApiResponse<List<Map<String, Object>>> policies() { return ApiResponse.ok(jdbc.queryForList("SELECT * FROM archive_policy ORDER BY id DESC")); }

    @PostMapping("/policies")
    public ApiResponse<Map<String, Object>> createPolicy(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO archive_policy(name,condition_type,condition_value,target_storage_id,enabled) VALUES (?,'UPLOAD_AGE_DAYS',?,?,?)",
                body.get("name"), body.get("conditionValue"), body.get("targetStorageId"), body.getOrDefault("enabled", true));
        return ApiResponse.ok(Map.of("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }
}
