package com.medreport.slide.storage;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/storage-targets")
public class StorageTargetController {
    private final StorageTargetService targets;
    private final S3StorageProvider storage;

    public StorageTargetController(StorageTargetService targets, S3StorageProvider storage) {
        this.targets = targets;
        this.storage = storage;
    }

    @GetMapping
    @RequirePermission({"SLIDE_ARCHIVE","SYSTEM_CONFIG"})
    public ApiResponse<List<Map<String, Object>>> list() { return ApiResponse.ok(targets.listSafe()); }

    @PostMapping
    @RequirePermission("SYSTEM_CONFIG")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        long id = targets.save(null, body); return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/{id}")
    @RequirePermission("SYSTEM_CONFIG")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        targets.save(id, body); return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @RequirePermission("SYSTEM_CONFIG")
    public ApiResponse<Map<String, Object>> test(@PathVariable long id) {
        StorageTarget target = targets.find(id); storage.ensureBucket(target);
        return ApiResponse.ok(Map.of("status", "UP", "bucket", target.bucket()));
    }
}
