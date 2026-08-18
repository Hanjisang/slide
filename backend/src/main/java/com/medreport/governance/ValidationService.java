package com.medreport.governance;

import com.medreport.common.BizException;
import com.medreport.medical.MedicalDataService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ValidationService {
    private final JdbcTemplate jdbc;
    private final MedicalDataService medicalDataService;

    public ValidationService(JdbcTemplate jdbc, MedicalDataService medicalDataService) {
        this.jdbc = jdbc;
        this.medicalDataService = medicalDataService;
    }

    @Transactional
    public boolean validate(String businessType, long businessId) {
        DataDefinition definition = definition(businessType);
        List<Map<String, Object>> dataRows = jdbc.queryForList("SELECT * FROM " + definition.table() + " WHERE id=?", businessId);
        if (dataRows.isEmpty()) throw new BizException("待校验数据不存在");
        Map<String, Object> data = dataRows.getFirst();
        jdbc.update("DELETE FROM validation_error WHERE business_type=? AND business_id=? AND status='PENDING'", businessType, businessId);
        List<Map<String, Object>> rules = jdbc.queryForList("SELECT * FROM validation_rule WHERE business_type=? AND enabled=1", businessType);
        int failures = 0;
        for (Map<String, Object> rule : rules) {
            String field = String.valueOf(rule.get("field_name"));
            Object value = lookup(data, field);
            String ruleType = String.valueOf(rule.get("rule_type"));
            String config = rule.get("rule_config") == null ? null : String.valueOf(rule.get("rule_config"));
            boolean passed = switch (ruleType) {
                case "UNIQUE" -> unique(definition, field, value, businessId);
                case "CROSS_FIELD" -> crossField(data, field, config);
                case "CROSS_RECORD" -> crossRecord(businessType, data, field, value);
                default -> passes(ruleType, value, config);
            };
            if (!passed) {
                jdbc.update("""
                        INSERT INTO validation_error(business_type,business_id,patient_id,field_name,current_value,rule_id,rule_type,error_message,status)
                        VALUES (?,?,?,?,?,?,?,?, 'PENDING')
                        """, businessType, businessId, businessType.equals("PATIENT") ? businessId : data.get("patient_id"), field,
                        value == null ? null : String.valueOf(value), rule.get("id"), ruleType, rule.get("error_message"));
                failures++;
            }
        }
        if (businessType.equals("PATIENT")) jdbc.update("UPDATE patient SET quality_status=? WHERE id=?", failures == 0 ? "PASSED" : "FAILED", businessId);
        return failures == 0;
    }

    private record DataDefinition(String table, Set<String> fields) {}

    private DataDefinition definition(String businessType) {
        if ("PATHOLOGY_CASE".equalsIgnoreCase(businessType)) return new DataDefinition("pathology_case",
                Set.of("pathology_no","patient_id","visit_id","specimen_name","specimen_type_code","clinical_diagnosis","pathology_diagnosis","case_status"));
        MedicalDataService.Definition current = medicalDataService.definition(businessType);
        return new DataDefinition(current.table(), current.fields());
    }

    private boolean unique(DataDefinition definition, String field, Object value, long id) {
        if (value == null || String.valueOf(value).isBlank()) return true;
        if (!definition.fields().contains(field)) throw new BizException("UNIQUE 规则字段无效: " + field);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + definition.table() + " WHERE " + field + "=? AND id<>?", Integer.class, value, id);
        return count == null || count == 0;
    }

    private boolean crossField(Map<String, Object> data, String field, String config) {
        if (config == null || config.isBlank()) return true;
        String[] parts = config.split(",", 2);
        String operator = parts.length == 2 ? parts[0].trim() : "<=";
        String other = parts.length == 2 ? parts[1].trim() : parts[0].trim();
        Object left = lookup(data, field), right = lookup(data, other);
        if (left == null || right == null) return true;
        int compared = String.valueOf(left).compareTo(String.valueOf(right));
        return switch (operator) { case "<=" -> compared <= 0; case "<" -> compared < 0; case ">=" -> compared >= 0;
            case ">" -> compared > 0; case "==", "=" -> compared == 0; default -> false; };
    }

    private boolean crossRecord(String businessType, Map<String, Object> data, String field, Object value) {
        if (!"PATIENT".equalsIgnoreCase(businessType) || !"gender".equalsIgnoreCase(field) || value == null) return true;
        Object patientNo = lookup(data, "patient_no");
        if (patientNo == null) return true;
        Integer inconsistent = jdbc.queryForObject("SELECT COUNT(*) FROM patient WHERE patient_no=? AND id<>? AND gender IS NOT NULL AND gender<>?",
                Integer.class, patientNo, data.get("id"), value);
        return inconsistent == null || inconsistent == 0;
    }

    public boolean passes(String ruleType, Object value, String config) {
        String text = value == null ? null : String.valueOf(value).trim();
        return switch (ruleType) {
            case "NOT_NULL" -> text != null && !text.isBlank();
            case "MIN" -> empty(text) || number(text) >= Double.parseDouble(config);
            case "MAX" -> empty(text) || number(text) <= Double.parseDouble(config);
            case "RANGE" -> empty(text) || inRange(text, config);
            case "REGEX" -> empty(text) || Pattern.matches(config, text);
            case "ENUM" -> empty(text) || Arrays.asList(config.split(",")).contains(text);
            case "DATE_RANGE" -> empty(text) || dateInRange(text, config);
            case "LENGTH" -> empty(text) || lengthInRange(text, config);
            default -> true;
        };
    }

    private Object lookup(Map<String, Object> data, String field) {
        for (Map.Entry<String, Object> entry : data.entrySet()) if (entry.getKey().equalsIgnoreCase(field)) return entry.getValue();
        return null;
    }

    private boolean empty(String text) { return text == null || text.isBlank(); }
    private double number(String text) { try { return Double.parseDouble(text); } catch (Exception ex) { return Double.NaN; } }
    private boolean inRange(String text, String config) {
        String[] p = config.split(",", 2); double value = number(text);
        return !Double.isNaN(value) && value >= Double.parseDouble(p[0]) && value <= Double.parseDouble(p[1]);
    }
    private boolean dateInRange(String text, String config) {
        String[] p = config.split(",", 2); LocalDate value = LocalDate.parse(text.substring(0, 10));
        return !value.isBefore(LocalDate.parse(p[0])) && !value.isAfter(LocalDate.parse(p[1]));
    }
    private boolean lengthInRange(String text, String config) {
        String[] p = config.split(",", 2); int min = Integer.parseInt(p[0]); int max = p.length == 1 ? min : Integer.parseInt(p[1]);
        return text.length() >= min && text.length() <= max;
    }
}
