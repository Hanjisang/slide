package com.medreport.system;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@RequirePermission("MONITOR_VIEW")
public class AlertController {
    private final JdbcTemplate jdbc;
    public AlertController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping("/events")public ApiResponse<List<Map<String,Object>>> events(){return ApiResponse.ok(jdbc.queryForList("SELECT * FROM alert_event ORDER BY id DESC LIMIT 500"));}
    @GetMapping("/rules")public ApiResponse<List<Map<String,Object>>> rules(){return ApiResponse.ok(jdbc.queryForList("SELECT * FROM alert_rule ORDER BY id"));}
    @PostMapping("/rules")public ApiResponse<Map<String,Object>> rule(@RequestBody Map<String,Object> body){jdbc.update("INSERT INTO alert_rule(name,rule_type,threshold_value,severity,enabled) VALUES (?,?,?,?,?)",
            body.get("name"),body.get("ruleType"),body.get("thresholdValue"),body.getOrDefault("severity","WARNING"),body.getOrDefault("enabled",true));return ApiResponse.ok(Map.of("id",jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class)));}
    @PostMapping("/events/{id}/acknowledge")public ApiResponse<Void> acknowledge(@PathVariable long id,HttpServletRequest request){jdbc.update("UPDATE alert_event SET status='ACKNOWLEDGED',acknowledged_by=?,acknowledged_at=NOW() WHERE id=?",AuditService.username(request),id);return ApiResponse.ok();}
    @PostMapping("/events/{id}/close")public ApiResponse<Void> close(@PathVariable long id,HttpServletRequest request){jdbc.update("UPDATE alert_event SET status='CLOSED',closed_by=?,closed_at=NOW() WHERE id=?",AuditService.username(request),id);return ApiResponse.ok();}
}
