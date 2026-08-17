package com.medreport.system;

import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final JdbcTemplate jdbc;
    private final MinioClient minio;
    private final RestClient worker;
    private final SystemConfigMapper configMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public SystemController(JdbcTemplate jdbc, MinioClient minio, RestClient.Builder builder, SystemConfigMapper configMapper,
                            @Value("${app.slide-worker-url}") String workerUrl) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.worker = builder.baseUrl(workerUrl).build();
        this.configMapper = configMapper;
    }

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() { return ApiResponse.ok(Map.of("status", "UP", "time", LocalDateTime.now())); }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("mysql", checkMysql());
        components.put("minio", storageHealth());
        components.put("slideWorker", checkWorker());
        Map<String, Object> queues = new LinkedHashMap<>();
        queues.put("pendingCollectTasks", count("SELECT COUNT(*) FROM collect_task WHERE enabled=1 AND (next_run_time IS NULL OR next_run_time<=NOW())"));
        queues.put("failedCollectTasks", count("SELECT COUNT(*) FROM collect_log WHERE status='FAILED' AND created_at>=CURRENT_DATE"));
        queues.put("pendingSlides", count("SELECT COUNT(*) FROM slide_file WHERE status IN ('UPLOADING','UPLOADED','PARSING')"));
        queues.put("failedSlides", count("SELECT COUNT(*) FROM slide_file WHERE status='FAILED'"));
        queues.put("pendingReports", count("SELECT COUNT(*) FROM report_batch WHERE status IN ('PENDING','GENERATING','READY','REPORTING')"));
        queues.put("failedReports", count("SELECT COUNT(*) FROM report_batch WHERE status='FAILED'"));
        return ApiResponse.ok(Map.of("components", components, "queues", queues));
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        Map<String, Object> today = new LinkedHashMap<>();
        today.put("collected", count("SELECT COALESCE(SUM(success_count),0) FROM collect_log WHERE started_at>=CURRENT_DATE"));
        today.put("errors", count("SELECT COUNT(*) FROM validation_error WHERE created_at>=CURRENT_DATE"));
        today.put("reportedSuccess", count("SELECT COALESCE(SUM(success_count),0) FROM report_batch WHERE status='SUCCESS' AND reported_at>=CURRENT_DATE"));
        today.put("reportedFailed", count("SELECT COALESCE(SUM(failed_count),0) FROM report_batch WHERE status='FAILED' AND updated_at>=CURRENT_DATE"));
        today.put("newSlides", count("SELECT COUNT(*) FROM slide_file WHERE created_at>=CURRENT_DATE"));
        today.put("processingSlides", count("SELECT COUNT(*) FROM slide_file WHERE status IN ('UPLOADING','UPLOADED','PARSING')"));
        return ApiResponse.ok(Map.of("today", today, "health", health().data()));
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> users() {
        return ApiResponse.ok(jdbc.queryForList("SELECT id,username,display_name,role,enabled,created_at,updated_at FROM sys_user ORDER BY id"));
    }

    @PostMapping("/users")
    public ApiResponse<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String password = String.valueOf(body.getOrDefault("password", ""));
        if (username.isBlank() || password.length() < 8) throw new BizException("用户名不能为空，密码至少 8 位");
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,enabled) VALUES (?,?,?,?,1)", username,
                passwordEncoder.encode(password), body.getOrDefault("displayName", username), body.getOrDefault("role", "USER"));
        return ApiResponse.ok(Map.of("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<Void> updateUser(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        if (!password.isBlank()) {
            if (password.length() < 8) throw new BizException("密码至少 8 位");
            jdbc.update("UPDATE sys_user SET display_name=?,role=?,enabled=?,password_hash=? WHERE id=?", body.get("displayName"), body.get("role"), bool(body.get("enabled")), passwordEncoder.encode(password), id);
        } else jdbc.update("UPDATE sys_user SET display_name=?,role=?,enabled=? WHERE id=?", body.get("displayName"), body.get("role"), bool(body.get("enabled")), id);
        return ApiResponse.ok();
    }

    @GetMapping("/configs")
    public ApiResponse<List<Map<String, Object>>> configs() { return ApiResponse.ok(configMapper.listAll()); }

    @PutMapping("/configs/{id}")
    public ApiResponse<Void> config(@PathVariable long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE system_config SET config_value=?,description=? WHERE id=?", body.get("configValue"), body.get("description"), id);
        return ApiResponse.ok();
    }

    @GetMapping("/operation-logs")
    public ApiResponse<List<Map<String, Object>>> logs() { return ApiResponse.ok(jdbc.queryForList("SELECT * FROM operation_log ORDER BY id DESC LIMIT 500")); }

    @GetMapping("/adapters")
    @SuppressWarnings("unchecked")
    public ApiResponse<Object> adapters() {
        try { return ApiResponse.ok(worker.get().uri("/api/adapters").retrieve().body(List.class)); }
        catch (Exception ex) { return ApiResponse.ok(List.of()); }
    }

    private Map<String, Object> checkMysql() {
        try { jdbc.queryForObject("SELECT 1", Integer.class); return Map.of("status", "UP"); }
        catch (Exception ex) { return Map.of("status", "DOWN", "message", ex.getMessage()); }
    }

    private Map<String, Object> checkWorker() {
        try { Map<?, ?> result = worker.get().uri("/health").retrieve().body(Map.class); return Map.of("status", "UP", "detail", result == null ? Map.of() : result); }
        catch (Exception ex) { return Map.of("status", "DOWN", "message", ex.getMessage()); }
    }

    private Map<String, Object> storageHealth() {
        try {
            long bytes = 0, objects = 0;
            for (String bucket : List.of("pathology-original", "pathology-cache")) {
                for (Result<Item> result : minio.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                    Item item = result.get(); bytes += item.size(); objects++;
                }
            }
            return Map.of("status", "UP", "usedBytes", bytes, "objectCount", objects, "slideCount", count("SELECT COUNT(*) FROM slide_file"));
        } catch (Exception ex) { return Map.of("status", "DOWN", "message", ex.getMessage()); }
    }

    private long count(String sql) { Number value = jdbc.queryForObject(sql, Number.class); return value == null ? 0 : value.longValue(); }
    private boolean bool(Object value) { return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue()!=0 : Boolean.parseBoolean(String.valueOf(value)); }
}
