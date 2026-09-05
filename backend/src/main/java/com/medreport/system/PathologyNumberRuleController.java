package com.medreport.system;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/pathology-number-rules")
@RequirePermission("DICT_MANAGE")
public class PathologyNumberRuleController {
    private static final Set<String> FIELDS = Set.of("name", "business_type", "prefix", "year_format", "separator", "sequence_digits", "start_sequence", "enabled");
    private final JdbcTemplate jdbc;

    public PathologyNumberRuleController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(jdbc.queryForList("SELECT * FROM pathology_no_rule ORDER BY id DESC"));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> data = validate(body);
        jdbc.update("INSERT INTO pathology_no_rule(name,business_type,prefix,year_format,`separator`,sequence_digits,start_sequence,enabled) VALUES (?,?,?,?,?,?,?,?)",
                data.get("name"), data.get("business_type"), data.get("prefix"), data.get("year_format"), data.get("separator"),
                data.get("sequence_digits"), data.get("start_sequence"), data.get("enabled"));
        return ApiResponse.ok(find(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> data = validate(body);
        int count = jdbc.update("UPDATE pathology_no_rule SET name=?,business_type=?,prefix=?,year_format=?,`separator`=?,sequence_digits=?,start_sequence=?,enabled=? WHERE id=?",
                data.get("name"), data.get("business_type"), data.get("prefix"), data.get("year_format"), data.get("separator"),
                data.get("sequence_digits"), data.get("start_sequence"), data.get("enabled"), id);
        if (count == 0) throw new BizException("病理号规则不存在");
        return ApiResponse.ok(find(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (jdbc.update("DELETE FROM pathology_no_rule WHERE id=?", id) == 0) throw new BizException("病理号规则不存在");
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/preview")
    public ApiResponse<Map<String, Object>> preview(@PathVariable long id) {
        return ApiResponse.ok(Map.of("example", format(find(id))));
    }

    private Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM pathology_no_rule WHERE id=?", id);
        if (rows.isEmpty()) throw new BizException("病理号规则不存在");
        return rows.getFirst();
    }

    private Map<String, Object> validate(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String name = text(body, "name", "规则名称");
        String yearFormat = text(body, "yearFormat", "年份格式");
        String separator = String.valueOf(body.getOrDefault("separator", "-"));
        int digits = number(body.getOrDefault("sequenceDigits", 6), "流水号位数");
        int start = number(body.getOrDefault("startSequence", 1), "起始流水号");
        if (digits < 1 || digits > 12) throw new BizException("流水号位数应为 1-12");
        if (start < 0) throw new BizException("起始流水号不能小于 0");
        try { DateTimeFormatter.ofPattern(yearFormat); } catch (IllegalArgumentException ex) { throw new BizException("年份格式无效"); }
        result.put("name", name);
        result.put("business_type", body.getOrDefault("businessType", "PATHOLOGY_CASE"));
        result.put("prefix", body.getOrDefault("prefix", ""));
        result.put("year_format", yearFormat);
        result.put("separator", separator);
        result.put("sequence_digits", digits);
        result.put("start_sequence", start);
        result.put("enabled", bool(body.getOrDefault("enabled", true)) ? 1 : 0);
        return result;
    }

    private String format(Map<String, Object> row) {
        String year = LocalDate.now().format(DateTimeFormatter.ofPattern(String.valueOf(row.get("year_format"))));
        String prefix = String.valueOf(row.getOrDefault("prefix", ""));
        String separator = String.valueOf(row.getOrDefault("separator", "-"));
        int digits = ((Number) row.get("sequence_digits")).intValue();
        int sequence = ((Number) row.get("start_sequence")).intValue();
        return String.join(separator, Arrays.asList(prefix, year, String.format("%0" + digits + "d", sequence))).replaceFirst("^" + java.util.regex.Pattern.quote(separator), "");
    }

    private String text(Map<String, Object> body, String key, String label) {
        String camel = String.valueOf(body.getOrDefault(key, "")).trim();
        if (camel.isBlank()) {
            String snake = key.replaceAll("([a-z])([A-Z])", "$1_$2");
            camel = String.valueOf(body.getOrDefault(snake, "")).trim();
        }
        if (camel.isBlank()) throw new BizException(label + "不能为空");
        return camel;
    }
    private int number(Object value, String label) { try { return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)); } catch (Exception ex) { throw new BizException(label + "无效"); } }
    private boolean bool(Object value) { return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0 || Boolean.parseBoolean(String.valueOf(value)); }
}
