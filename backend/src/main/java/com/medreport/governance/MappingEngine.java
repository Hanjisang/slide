package com.medreport.governance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class MappingEngine {
    private final JdbcTemplate jdbc;

    public MappingEngine(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> transform(String businessType, String sourceSystem, Map<String, Object> source) {
        List<Map<String, Object>> fields = jdbc.queryForList("""
                SELECT f.* FROM mapping_field f JOIN mapping_template t ON t.id=f.template_id
                WHERE t.business_type=? AND t.source_system=? AND t.enabled=1 ORDER BY f.sort_order,f.id
                """, businessType, sourceSystem);
        if (fields.isEmpty()) return normalizeKeys(source);
        Map<String, Object> normalized = normalizeKeys(source);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String sourceField = lower(field.get("source_field"));
            String targetField = lower(field.get("target_field"));
            String rule = String.valueOf(field.get("rule_type"));
            String config = field.get("rule_config") == null ? null : String.valueOf(field.get("rule_config"));
            Object value = apply(rule, sourceField == null ? null : normalized.get(sourceField), config, normalized);
            result.put(targetField, value);
        }
        return result;
    }

    private Object apply(String rule, Object value, String config, Map<String, Object> source) {
        return switch (rule) {
            case "TRIM" -> value == null ? null : String.valueOf(value).trim();
            case "UPPERCASE" -> value == null ? null : String.valueOf(value).toUpperCase(Locale.ROOT);
            case "LOWERCASE" -> value == null ? null : String.valueOf(value).toLowerCase(Locale.ROOT);
            case "NUMBER" -> value == null || String.valueOf(value).isBlank() ? null : new BigDecimal(String.valueOf(value));
            case "DEFAULT_VALUE" -> value == null || String.valueOf(value).isBlank() ? config : value;
            case "REPLACE" -> replace(value, config);
            case "DICTIONARY" -> dictionary(config, value);
            case "CONCAT" -> concat(config, source);
            case "DATE_FORMAT" -> formatDate(value, config);
            default -> value;
        };
    }

    private Object dictionary(String type, Object value) {
        if (value == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.target_value FROM dictionary_item i JOIN dictionary d ON d.id=i.dictionary_id
                WHERE d.dict_type=? AND i.source_value=?
                """, type, String.valueOf(value));
        return rows.isEmpty() ? value : rows.getFirst().get("target_value");
    }

    private Object replace(Object value, String config) {
        if (value == null || config == null) return value;
        String[] parts = config.split(",", 2);
        return parts.length == 2 ? String.valueOf(value).replace(parts[0], parts[1]) : value;
    }

    private String concat(String config, Map<String, Object> source) {
        if (config == null) return "";
        return Arrays.stream(config.split(",")).map(String::trim).map(source::get).filter(Objects::nonNull)
                .map(String::valueOf).reduce("", (a, b) -> a + b);
    }

    private Object formatDate(Object value, String config) {
        if (value == null) return null;
        String pattern = config == null || config.isBlank() ? "yyyy-MM-dd" : config;
        if (value instanceof Date date) return date.toLocalDate().format(DateTimeFormatter.ofPattern(pattern));
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().format(DateTimeFormatter.ofPattern(pattern));
        if (value instanceof LocalDate date) return date.format(DateTimeFormatter.ofPattern(pattern));
        if (value instanceof LocalDateTime dateTime) return dateTime.format(DateTimeFormatter.ofPattern(pattern));
        return value;
    }

    private Map<String, Object> normalizeKeys(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }

    private String lower(Object value) { return value == null ? null : String.valueOf(value).toLowerCase(Locale.ROOT); }
}

