package com.medreport.datasource;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
import com.medreport.security.SecretCipher;
import com.medreport.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final AuditService audit;

    public DataSourceController(JdbcTemplate jdbc, SecretCipher cipher, AuditService audit) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
    }

    @GetMapping
    @RequirePermission("DATA_VIEW")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(jdbc.queryForList("""
                SELECT id,name,code,connector_type,system_type,database_type,host,port,database_name,username,jdbc_url,api_url,file_path,enabled,created_at,updated_at
                FROM data_source_config ORDER BY id DESC
                """));
    }

    @PostMapping
    @RequirePermission("DATA_EDIT")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        require(body, "name", "code", "connectorType", "systemType");
        jdbc.update("""
                INSERT INTO data_source_config(name,code,connector_type,system_type,database_type,host,port,database_name,username,password_encrypted,jdbc_url,api_url,file_path,enabled)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, body.get("name"), body.get("code"), body.get("connectorType"), body.get("systemType"), body.get("databaseType"),
                body.get("host"), body.get("port"), body.get("databaseName"), body.get("username"), cipher.encrypt(string(body.get("password"))),
                body.get("jdbcUrl"), body.get("apiUrl"), body.get("filePath"), bool(body.getOrDefault("enabled", true)));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        audit.log(request, "新增数据源", "数据源管理", id, "SUCCESS", String.valueOf(body.get("code")));
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/{id}")
    @RequirePermission("DATA_EDIT")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        require(body, "name", "code", "connectorType", "systemType");
        String password = string(body.get("password"));
        jdbc.update("""
                UPDATE data_source_config SET name=?,code=?,connector_type=?,system_type=?,database_type=?,host=?,port=?,database_name=?,username=?,
                password_encrypted=CASE WHEN ? IS NULL OR ?='' THEN password_encrypted ELSE ? END,jdbc_url=?,api_url=?,file_path=?,enabled=? WHERE id=?
                """, body.get("name"), body.get("code"), body.get("connectorType"), body.get("systemType"), body.get("databaseType"),
                body.get("host"), body.get("port"), body.get("databaseName"), body.get("username"), password, password,
                password == null || password.isBlank() ? null : cipher.encrypt(password), body.get("jdbcUrl"), body.get("apiUrl"), body.get("filePath"),
                bool(body.getOrDefault("enabled", true)), id);
        audit.log(request, "修改数据源", "数据源管理", id, "SUCCESS", String.valueOf(body.get("code")));
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    @RequirePermission("DATA_EDIT")
    public ApiResponse<Void> status(@PathVariable long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE data_source_config SET enabled=? WHERE id=?", bool(body.get("enabled")), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/test")
    @RequirePermission("DATA_EDIT")
    public ApiResponse<Map<String, Object>> test(@PathVariable long id) {
        Map<String, Object> source = find(id);
        if (!"DATABASE".equals(source.get("connector_type"))) {
            return ApiResponse.ok(Map.of("success", true, "message", "配置有效"));
        }
        long started = System.currentTimeMillis();
        try (Connection ignored = DriverManager.getConnection(String.valueOf(source.get("jdbc_url")),
                string(source.get("username")), cipher.decrypt(string(source.get("password_encrypted"))))) {
            return ApiResponse.ok(Map.of("success", true, "message", "连接成功", "latencyMs", System.currentTimeMillis() - started));
        } catch (Exception ex) {
            throw new BizException("连接失败: " + ex.getMessage());
        }
    }

    public Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM data_source_config WHERE id=?", id);
        if (rows.isEmpty()) throw new BizException("数据源不存在");
        return rows.getFirst();
    }

    private void require(Map<String, Object> body, String... fields) {
        for (String field : fields) if (string(body.get(field)) == null || string(body.get(field)).isBlank()) throw new BizException(field + " 不能为空");
    }

    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean bool(Object value) { return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue() != 0 : Boolean.parseBoolean(String.valueOf(value)); }
}
