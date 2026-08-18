package com.medreport.system;

import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/configuration")
public class BusinessConfigController {
    private record Resource(String table, Set<String> fields) {}
    private static final Map<String, Resource> RESOURCES = Map.of(
            "pathology-cases", new Resource("pathology_case", Set.of("patient_id","visit_id","pathology_no","specimen_name","specimen_type_code","clinical_diagnosis","pathology_diagnosis","case_status")),
            "report-templates", new Resource("report_template", Set.of("name","report_type","format","sender_type","endpoint","include_slide","business_type","schedule_enabled","enabled"))
    );
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public BusinessConfigController(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    @GetMapping("/{name}")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable String name) {
        Resource resource = resource(name); return ApiResponse.ok(jdbc.queryForList("SELECT * FROM " + resource.table() + " ORDER BY id DESC"));
    }

    @PostMapping("/{name}")
    public ApiResponse<Map<String, Object>> create(@PathVariable String name, @RequestBody Map<String, Object> body) {
        Resource resource = resource(name); Map<String, Object> data = sanitize(body, resource.fields());
        if ("pathology-cases".equals(name)) checkDuplicatePathology(data);
        List<String> fields = new ArrayList<>(data.keySet());
        jdbc.update("INSERT INTO " + resource.table() + " (" + String.join(",", fields) + ") VALUES (" + String.join(",", Collections.nCopies(fields.size(), "?")) + ")", fields.stream().map(data::get).toArray());
        return ApiResponse.ok(Map.of("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    @PutMapping("/{name}/{id}")
    public ApiResponse<Void> update(@PathVariable String name, @PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Resource resource = resource(name); Map<String, Object> data = sanitize(body, resource.fields()); List<String> fields = new ArrayList<>(data.keySet());
        Object[] args = Arrays.copyOf(fields.stream().map(data::get).toArray(), fields.size()+1); args[fields.size()] = id;
        jdbc.update("UPDATE " + resource.table() + " SET " + fields.stream().map(f -> f + "=?").collect(Collectors.joining(",")) + " WHERE id=?", args);
        audit.log(request, "修改业务配置", name.equals("pathology-cases") ? "数字切片" : "数据上报", id, "SUCCESS", name);
        return ApiResponse.ok();
    }

    private Resource resource(String name) { Resource result = RESOURCES.get(name); if (result == null) throw new BizException("不支持的配置资源"); return result; }
    private Map<String, Object> sanitize(Map<String, Object> body, Set<String> fields) {
        Map<String, Object> data = new LinkedHashMap<>(); body.forEach((key,value) -> { String snake=key.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT); if(fields.contains(snake)) data.put(snake,value); });
        if (data.isEmpty()) throw new BizException("没有可保存的字段"); return data;
    }

    private void checkDuplicatePathology(Map<String,Object> data) {
        Object pathologyNo=data.get("pathology_no"); if(pathologyNo==null)return;
        List<Map<String,Object>> existing=jdbc.queryForList("SELECT id,patient_id FROM pathology_case WHERE pathology_no=?",pathologyNo);
        if(existing.isEmpty())return;
        List<Map<String,Object>> rules=jdbc.queryForList("SELECT * FROM validation_rule WHERE business_type='PATHOLOGY_CASE' AND field_name='pathology_no' AND rule_type='UNIQUE' LIMIT 1");
        if(!rules.isEmpty()){
            Map<String,Object> row=existing.getFirst(),rule=rules.getFirst();
            jdbc.update("""
                    INSERT INTO validation_error(business_type,business_id,patient_id,field_name,current_value,rule_id,rule_type,error_message,status)
                    VALUES ('PATHOLOGY_CASE',?,?,'pathology_no',?,?,'UNIQUE','病理号重复','PENDING')
                    """,row.get("id"),row.get("patient_id"),pathologyNo,rule.get("id"));
        }
        throw new BizException("病理号重复");
    }
}
