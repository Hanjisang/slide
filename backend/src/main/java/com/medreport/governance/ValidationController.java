package com.medreport.governance;

import com.medreport.auth.AuthInterceptor;
import com.medreport.auth.RequirePermission;
import com.medreport.auth.TokenService;
import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
import com.medreport.medical.MedicalDataService;
import com.medreport.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/validation-errors")
public class ValidationController {
    private final JdbcTemplate jdbc;
    private final MedicalDataService medicalData;
    private final ValidationService validation;
    private final AuditService audit;

    public ValidationController(JdbcTemplate jdbc, MedicalDataService medicalData, ValidationService validation, AuditService audit) {
        this.jdbc = jdbc;
        this.medicalData = medicalData;
        this.validation = validation;
        this.audit = audit;
    }

    @GetMapping
    @RequirePermission({"DATA_VIEW","QUALITY_MANAGE"})
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        String filter = status == null || status.isBlank() ? "" : " WHERE e.status=? ";
        String sql = """
                SELECT e.*,p.name patient_name,r.rule_config FROM validation_error e
                LEFT JOIN patient p ON p.id=e.patient_id LEFT JOIN validation_rule r ON r.id=e.rule_id
                """ + filter + " ORDER BY e.id DESC LIMIT 500";
        return ApiResponse.ok(filter.isBlank() ? jdbc.queryForList(sql) : jdbc.queryForList(sql, status));
    }

    @PutMapping("/{id}/value")
    @RequirePermission("QUALITY_MANAGE")
    public ApiResponse<Void> correct(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> error = find(id);
        if (!"PENDING".equals(error.get("status")) && !"FAILED".equals(error.get("status"))) throw new BizException("当前异常状态不允许修改");
        Object newValue = body.get("value");
        String businessType = String.valueOf(error.get("business_type"));
        long businessId = ((Number) error.get("business_id")).longValue();
        if ("PATHOLOGY_CASE".equals(businessType)) {
            jdbc.update("UPDATE pathology_case SET " + safePathologyField(String.valueOf(error.get("field_name"))) + "=? WHERE id=?", newValue, businessId);
        } else medicalData.update(businessType, businessId, Map.of(String.valueOf(error.get("field_name")), newValue));
        TokenService.Session session = (TokenService.Session) request.getAttribute(AuthInterceptor.SESSION_ATTRIBUTE);
        jdbc.update("UPDATE validation_error SET current_value=?,status='CORRECTED',handled_by=?,handled_at=NOW(),handle_note=? WHERE id=?",
                newValue, session.username(), body.get("note"), id);
        jdbc.update("INSERT INTO validation_handle_log(error_id,username,old_value,new_value,action,result) VALUES (?,?,?,?,?,?)",
                id, session.username(), error.get("current_value"), newValue, "CORRECT", "SUCCESS");
        audit.log(request, "修改异常数据", "数据质量", businessId, "SUCCESS", String.valueOf(error.get("field_name")));
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/revalidate")
    @RequirePermission("QUALITY_MANAGE")
    public ApiResponse<Map<String, Object>> revalidate(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> error = find(id);
        String businessType = String.valueOf(error.get("business_type"));
        long businessId = ((Number) error.get("business_id")).longValue();
        boolean passed = validation.validate(businessType, businessId);
        TokenService.Session session = (TokenService.Session) request.getAttribute(AuthInterceptor.SESSION_ATTRIBUTE);
        jdbc.update("UPDATE validation_error SET status=?,handled_by=?,handled_at=NOW() WHERE id=?", passed ? "PASSED" : "FAILED", session.username(), id);
        jdbc.update("INSERT INTO validation_handle_log(error_id,username,old_value,new_value,action,result) VALUES (?,?,?,?,?,?)",
                id, session.username(), error.get("current_value"), error.get("current_value"), "REVALIDATE", passed ? "PASSED" : "FAILED");
        audit.log(request, "重新校验异常", "数据质量", businessId, passed ? "SUCCESS" : "FAILED", String.valueOf(error.get("field_name")));
        return ApiResponse.ok(Map.of("passed", passed));
    }

    @PostMapping("/{id}/ignore")
    @RequirePermission("QUALITY_MANAGE")
    public ApiResponse<Void> ignore(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> error = find(id);
        TokenService.Session session = (TokenService.Session) request.getAttribute(AuthInterceptor.SESSION_ATTRIBUTE);
        jdbc.update("UPDATE validation_error SET status='IGNORED',handled_by=?,handled_at=NOW() WHERE id=?", session.username(), id);
        jdbc.update("INSERT INTO validation_handle_log(error_id,username,old_value,new_value,action,result) VALUES (?,?,?,?,?,?)",
                id, session.username(), error.get("current_value"), error.get("current_value"), "IGNORE", "SUCCESS");
        long businessId = ((Number) error.get("business_id")).longValue();
        int pending = jdbc.queryForObject("SELECT COUNT(*) FROM validation_error WHERE business_type=? AND business_id=? AND status IN ('PENDING','FAILED')",
                Integer.class, error.get("business_type"), businessId);
        if (pending == 0 && "PATIENT".equals(error.get("business_type"))) jdbc.update("UPDATE patient SET quality_status='PASSED' WHERE id=?", businessId);
        audit.log(request, "忽略异常", "数据质量", businessId, "SUCCESS", String.valueOf(error.get("field_name")));
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/logs")
    @RequirePermission({"DATA_VIEW","QUALITY_MANAGE"})
    public ApiResponse<List<Map<String, Object>>> logs(@PathVariable long id) {
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM validation_handle_log WHERE error_id=? ORDER BY id DESC", id));
    }

    private Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM validation_error WHERE id=?", id);
        if (rows.isEmpty()) throw new BizException("异常记录不存在");
        return rows.getFirst();
    }

    private String safePathologyField(String field) {
        if (!java.util.Set.of("pathology_no","specimen_name","specimen_type_code","clinical_diagnosis","pathology_diagnosis","case_status").contains(field)) {
            throw new BizException("不允许修正该字段");
        }
        return field;
    }
}
