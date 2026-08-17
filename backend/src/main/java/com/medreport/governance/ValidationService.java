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
        MedicalDataService.Definition definition = medicalDataService.definition(businessType);
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
            if (!passes(ruleType, value, config)) {
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

