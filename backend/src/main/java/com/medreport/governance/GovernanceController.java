package com.medreport.governance;

import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
import com.medreport.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/governance")
public class GovernanceController {
    private record Resource(String table, Set<String> fields, String orderBy) {}
    private static final Map<String, Resource> RESOURCES = Map.of(
            "mapping-templates", new Resource("mapping_template", Set.of("name","business_type","source_system","enabled"), "id DESC"),
            "mapping-fields", new Resource("mapping_field", Set.of("template_id","source_field","target_field","rule_type","rule_config","sort_order"), "template_id,sort_order,id"),
            "dictionaries", new Resource("dictionary", Set.of("dict_type","name","description"), "id DESC"),
            "dictionary-items", new Resource("dictionary_item", Set.of("dictionary_id","source_value","target_value","description"), "dictionary_id,id"),
            "validation-rules", new Resource("validation_rule", Set.of("business_type","field_name","rule_type","rule_config","error_message","enabled"), "id DESC")
    );
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public GovernanceController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/{resourceName}")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable String resourceName) {
        Resource resource = resource(resourceName);
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM " + resource.table() + " ORDER BY " + resource.orderBy()));
    }

    @PostMapping("/{resourceName}")
    public ApiResponse<Map<String, Object>> create(@PathVariable String resourceName, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Resource resource = resource(resourceName);
        Map<String, Object> data = sanitize(body, resource.fields());
        if (data.isEmpty()) throw new BizException("没有可保存的字段");
        List<String> columns = new ArrayList<>(data.keySet());
        jdbc.update("INSERT INTO " + resource.table() + " (" + String.join(",", columns) + ") VALUES (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")",
                columns.stream().map(data::get).toArray());
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        audit.log(request, "新增治理配置", "数据质量", id, "SUCCESS", resourceName);
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/{resourceName}/{id}")
    public ApiResponse<Void> update(@PathVariable String resourceName, @PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Resource resource = resource(resourceName);
        Map<String, Object> data = sanitize(body, resource.fields());
        if (data.isEmpty()) throw new BizException("没有可更新的字段");
        List<String> columns = new ArrayList<>(data.keySet());
        Object[] args = Arrays.copyOf(columns.stream().map(data::get).toArray(), columns.size() + 1);
        args[columns.size()] = id;
        jdbc.update("UPDATE " + resource.table() + " SET " + columns.stream().map(c -> c + "=?").collect(Collectors.joining(",")) + " WHERE id=?", args);
        audit.log(request, "修改治理配置", "数据质量", id, "SUCCESS", resourceName);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{resourceName}/{id}")
    public ApiResponse<Void> delete(@PathVariable String resourceName, @PathVariable long id) {
        Resource resource = resource(resourceName);
        jdbc.update("DELETE FROM " + resource.table() + " WHERE id=?", id);
        return ApiResponse.ok();
    }

    private Resource resource(String name) {
        Resource resource = RESOURCES.get(name);
        if (resource == null) throw new BizException("不支持的治理资源");
        return resource;
    }

    private Map<String, Object> sanitize(Map<String, Object> body, Set<String> fields) {
        Map<String, Object> data = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            String snake = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            if (fields.contains(snake)) data.put(snake, value);
        });
        return data;
    }
}

