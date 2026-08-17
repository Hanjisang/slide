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
            "pathology-cases", new Resource("pathology_case", Set.of("patient_id","visit_id","pathology_no","specimen_name","clinical_diagnosis","pathology_diagnosis","case_status")),
            "report-templates", new Resource("report_template", Set.of("name","report_type","format","sender_type","endpoint","enabled"))
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
}
