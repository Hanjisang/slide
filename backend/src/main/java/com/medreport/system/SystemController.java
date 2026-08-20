package com.medreport.system;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
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
    private final RestClient worker;
    private final SystemConfigMapper configMapper;
    private final SystemMetricsService metricsService;
    private final MonitoringSnapshotService snapshots;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public SystemController(JdbcTemplate jdbc, RestClient.Builder builder, SystemConfigMapper configMapper, SystemMetricsService metricsService, MonitoringSnapshotService snapshots,
                            @Value("${app.slide-worker-url}") String workerUrl) {
        this.jdbc = jdbc;
        this.worker = builder.baseUrl(workerUrl).build();
        this.configMapper = configMapper;
        this.metricsService = metricsService;
        this.snapshots = snapshots;
    }

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() { return ApiResponse.ok(Map.of("status", "UP", "time", LocalDateTime.now())); }

    @GetMapping("/health")
    @RequirePermission({"MONITOR_VIEW","DATA_VIEW"})
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> health() {
        Map<String,Object> snapshot=snapshots.snapshot();Map<String,Object> health=new LinkedHashMap<>();
        health.put("components",snapshot.get("components"));health.put("queues",snapshot.get("queues"));
        health.put("criticalAlerts",snapshot.get("criticalAlerts"));health.put("capturedAt",snapshot.get("capturedAt"));
        return ApiResponse.ok(health);
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

    private long count(String sql) { Number value = jdbc.queryForObject(sql, Number.class); return value == null ? 0 : value.longValue(); }
    private boolean bool(Object value) { return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue()!=0 : Boolean.parseBoolean(String.valueOf(value)); }
    private void requireRole(String role){if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_code=? AND enabled=1",Integer.class,role)==0)throw new BizException("角色不存在或已停用");}
}
