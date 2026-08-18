package com.medreport.system;

import com.medreport.auth.RequirePermission;
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
    private final SystemMetricsService metricsService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public SystemController(JdbcTemplate jdbc, MinioClient minio, RestClient.Builder builder, SystemConfigMapper configMapper, SystemMetricsService metricsService,
                            @Value("${app.slide-worker-url}") String workerUrl) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.worker = builder.baseUrl(workerUrl).build();
        this.configMapper = configMapper;
        this.metricsService = metricsService;
    }

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() { return ApiResponse.ok(Map.of("status", "UP", "time", LocalDateTime.now())); }

    @GetMapping("/health")
    @RequirePermission({"MONITOR_VIEW","DATA_VIEW"})
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("mysql", checkMysql());
        components.put("minio", storageHealth());
        Map<String, Object> workerHealth = checkWorker();
        components.put("slideWorker", workerHealth);
        components.put("goParser", goParserHealth(workerHealth));
        Map<String, Object> queues = new LinkedHashMap<>();
        queues.put("pendingCollectTasks", count("SELECT COUNT(*) FROM collect_task WHERE enabled=1 AND (next_run_time IS NULL OR next_run_time<=NOW())"));
        queues.put("failedCollectTasks", count("SELECT COUNT(*) FROM collect_log WHERE status='FAILED' AND created_at>=CURRENT_DATE"));
        queues.put("pendingSlides", count("SELECT COUNT(*) FROM slide_file WHERE status IN ('UPLOADING','UPLOADED','PARSING')"));
        queues.put("failedSlides", count("SELECT COUNT(*) FROM slide_file WHERE status='FAILED'"));
        queues.put("pendingReports", count("SELECT COUNT(*) FROM report_batch WHERE status IN ('PENDING','GENERATING','READY','REPORTING')"));
        queues.put("failedReports", count("SELECT COUNT(*) FROM report_batch WHERE status='FAILED'"));
        queues.put("pendingArchiveTasks", count("SELECT COUNT(*) FROM archive_task WHERE status IN ('PENDING','COPYING','VERIFYING')"));
        queues.put("failedArchiveTasks", count("SELECT COUNT(*) FROM archive_task WHERE status='FAILED'"));
        queues.put("pendingBackupTasks", count("SELECT COUNT(*) FROM backup_task WHERE status IN ('PENDING','COPYING','VERIFYING')"));
        queues.put("failedBackupTasks", count("SELECT COUNT(*) FROM backup_task WHERE status='FAILED'"));
        return ApiResponse.ok(Map.of("components", components, "queues", queues));
    }

    @GetMapping("/dashboard")
    @RequirePermission({"MONITOR_VIEW","DATA_VIEW"})
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

    @GetMapping("/metrics")
    @RequirePermission("MONITOR_VIEW")
    public ApiResponse<Map<String,Object>> metrics(){return ApiResponse.ok(metricsService.metrics());}

    @GetMapping("/users")
    @RequirePermission("USER_MANAGE")
    public ApiResponse<List<Map<String, Object>>> users() {
        return ApiResponse.ok(jdbc.queryForList("SELECT id,username,display_name,role,enabled,created_at,updated_at FROM sys_user ORDER BY id"));
    }

    @PostMapping("/users")
    @RequirePermission("USER_MANAGE")
    public ApiResponse<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String password = String.valueOf(body.getOrDefault("password", ""));
        if (username.isBlank() || password.length() < 8) throw new BizException("用户名不能为空，密码至少 8 位");
        String role=String.valueOf(body.getOrDefault("role","VIEWER")); requireRole(role);
        jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,enabled) VALUES (?,?,?,?,1)", username,
                passwordEncoder.encode(password), body.getOrDefault("displayName", username), role);
        return ApiResponse.ok(Map.of("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    @PutMapping("/users/{id}")
    @RequirePermission("USER_MANAGE")
    public ApiResponse<Void> updateUser(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password")); requireRole(String.valueOf(body.get("role")));
        if (!password.isBlank()) {
            if (password.length() < 8) throw new BizException("密码至少 8 位");
            jdbc.update("UPDATE sys_user SET display_name=?,role=?,enabled=?,password_hash=? WHERE id=?", body.get("displayName"), body.get("role"), bool(body.get("enabled")), passwordEncoder.encode(password), id);
        } else jdbc.update("UPDATE sys_user SET display_name=?,role=?,enabled=? WHERE id=?", body.get("displayName"), body.get("role"), bool(body.get("enabled")), id);
        return ApiResponse.ok();
    }

    @GetMapping("/configs")
    @RequirePermission("SYSTEM_CONFIG")
    public ApiResponse<List<Map<String, Object>>> configs() { return ApiResponse.ok(configMapper.listAll()); }

    @PutMapping("/configs/{id}")
    @RequirePermission("SYSTEM_CONFIG")
    public ApiResponse<Void> config(@PathVariable long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE system_config SET config_value=?,description=? WHERE id=?", body.get("configValue"), body.get("description"), id);
        return ApiResponse.ok();
    }

    @GetMapping("/operation-logs")
    @RequirePermission("LOG_VIEW")
    public ApiResponse<List<Map<String, Object>>> logs() { return ApiResponse.ok(jdbc.queryForList("SELECT * FROM operation_log ORDER BY id DESC LIMIT 500")); }

    @GetMapping("/adapters")
    @RequirePermission("SLIDE_VIEW")
    @SuppressWarnings("unchecked")
    public ApiResponse<Object> adapters() {
        try { return ApiResponse.ok(worker.get().uri("/api/adapters").retrieve().body(List.class)); }
        catch (Exception ex) { return ApiResponse.ok(List.of()); }
    }

    @GetMapping("/roles")
    @RequirePermission("USER_MANAGE")
    public ApiResponse<List<Map<String,Object>>> roles(){
        List<Map<String,Object>> roles=jdbc.queryForList("SELECT * FROM sys_role ORDER BY id");
        for(Map<String,Object> role:roles)role.put("permissions",jdbc.queryForList("""
                SELECT p.permission_code FROM sys_permission p JOIN sys_role_permission rp ON rp.permission_id=p.id
                WHERE rp.role_id=? ORDER BY p.permission_code
                """,String.class,role.get("id")));
        return ApiResponse.ok(roles);
    }

    @GetMapping("/permissions")
    @RequirePermission("USER_MANAGE")
    public ApiResponse<List<Map<String,Object>>> permissions(){return ApiResponse.ok(jdbc.queryForList("SELECT * FROM sys_permission ORDER BY permission_code"));}

    @PutMapping("/roles/{id}/permissions")
    @RequirePermission("USER_MANAGE")
    public ApiResponse<Void> rolePermissions(@PathVariable long id,@RequestBody Map<String,Object> body){
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=?",id);
        Object value=body.get("permissions");
        if(value instanceof List<?> list)for(Object code:list)jdbc.update("""
                INSERT IGNORE INTO sys_role_permission(role_id,permission_id) SELECT ?,id FROM sys_permission WHERE permission_code=?
                """,id,String.valueOf(code));
        return ApiResponse.ok();
    }

    private Map<String, Object> checkMysql() {
        try { jdbc.queryForObject("SELECT 1", Integer.class); return Map.of("status", "UP"); }
        catch (Exception ex) { return Map.of("status", "DOWN", "message", ex.getMessage()); }
    }

    private Map<String, Object> checkWorker() {
        try { Map<?, ?> result = worker.get().uri("/health").retrieve().body(Map.class); return Map.of("status", "UP", "detail", result == null ? Map.of() : result); }
        catch (Exception ex) { return Map.of("status", "DOWN", "message", ex.getMessage()); }
    }

    private Map<String, Object> goParserHealth(Map<String, Object> workerHealth) {
        Object detail = workerHealth.get("detail");
        if (detail instanceof Map<?, ?> workerDetail && workerDetail.get("goParser") instanceof Map<?, ?> parser) {
            Map<String, Object> result = new LinkedHashMap<>();
            parser.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of("status", "DOWN", "message", "Slide Worker 未返回 Go Parser 状态");
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
    private void requireRole(String role){if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_code=? AND enabled=1",Integer.class,role)==0)throw new BizException("角色不存在或已停用");}
}
