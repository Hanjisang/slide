package com.medreport.medical;

import com.medreport.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MedicalDataService {
    public record Definition(String table, Set<String> fields) {}
    private static final Map<String, Definition> DEFINITIONS = Map.of(
            "PATIENT", new Definition("patient", Set.of("source_system","source_id","patient_no","name","gender","birthday","age","id_card","phone","quality_status")),
            "VISIT", new Definition("visit", Set.of("source_system","source_id","patient_id","visit_type","visit_no","department_code","department_name","doctor_code","doctor_name","admission_time","discharge_time")),
            "DIAGNOSIS", new Definition("diagnosis", Set.of("source_system","source_id","patient_id","visit_id","diagnosis_code","diagnosis_name","diagnosis_type","diagnosis_time")),
            "LAB", new Definition("lab_result", Set.of("source_system","source_id","patient_id","visit_id","item_code","item_name","result_value","result_unit","reference_range","abnormal_flag","result_time")),
            "EXAM", new Definition("exam_result", Set.of("source_system","source_id","patient_id","visit_id","exam_code","exam_name","exam_part","exam_result","exam_conclusion","exam_time")),
            "OPERATION", new Definition("medical_operation", Set.of("source_system","source_id","patient_id","visit_id","operation_code","operation_name","operation_time","operator_name")),
            "MEDICATION", new Definition("medication", Set.of("source_system","source_id","patient_id","visit_id","drug_code","drug_name","dosage","unit","frequency","route","start_time","end_time"))
    );
    private final JdbcTemplate jdbc;

    public MedicalDataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Definition definition(String type) {
        Definition definition = DEFINITIONS.get(type.toUpperCase(Locale.ROOT));
        if (definition == null) throw new BizException("不支持的业务类型: " + type);
        return definition;
    }

    public long upsert(String type, Map<String, Object> raw) {
        Definition definition = definition(type);
        Map<String, Object> data = sanitize(raw, definition.fields());
        if (!data.containsKey("source_system") || !data.containsKey("source_id")) throw new BizException("采集数据缺少来源标识");
        List<String> columns = new ArrayList<>(data.keySet());
        String sql = "INSERT INTO " + definition.table() + " (" + String.join(",", columns) + ") VALUES (" +
                String.join(",", Collections.nCopies(columns.size(), "?")) + ") ON DUPLICATE KEY UPDATE " +
                columns.stream().filter(c -> !c.equals("source_system") && !c.equals("source_id"))
                        .map(c -> c + "=VALUES(" + c + ")").collect(Collectors.joining(","));
        jdbc.update(sql, columns.stream().map(data::get).toArray());
        return jdbc.queryForObject("SELECT id FROM " + definition.table() + " WHERE source_system=? AND source_id=?", Long.class,
                data.get("source_system"), data.get("source_id"));
    }

    public List<Map<String, Object>> list(String type, int page, int size, String keyword) {
        Definition definition = definition(type);
        int safeSize = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page - 1, 0) * safeSize;
        if (keyword == null || keyword.isBlank()) return jdbc.queryForList("SELECT * FROM " + definition.table() + " ORDER BY id DESC LIMIT ? OFFSET ?", safeSize, offset);
        String field = type.equalsIgnoreCase("PATIENT") ? "name" : type.equalsIgnoreCase("LAB") ? "item_name" : "source_id";
        return jdbc.queryForList("SELECT * FROM " + definition.table() + " WHERE " + field + " LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?", "%" + keyword + "%", safeSize, offset);
    }

    public long count(String type, String keyword) {
        Definition definition = definition(type);
        if (keyword == null || keyword.isBlank()) return jdbc.queryForObject("SELECT COUNT(*) FROM " + definition.table(), Long.class);
        String field = type.equalsIgnoreCase("PATIENT") ? "name" : type.equalsIgnoreCase("LAB") ? "item_name" : "source_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + definition.table() + " WHERE " + field + " LIKE ?", Long.class, "%" + keyword + "%");
    }

    public long create(String type, Map<String, Object> raw) {
        Definition definition = definition(type);
        Map<String, Object> data = sanitize(raw, definition.fields());
        if (data.isEmpty()) throw new BizException("没有可保存的字段");
        List<String> columns = new ArrayList<>(data.keySet());
        jdbc.update("INSERT INTO " + definition.table() + " (" + String.join(",", columns) + ") VALUES (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")",
                columns.stream().map(data::get).toArray());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void update(String type, long id, Map<String, Object> raw) {
        Definition definition = definition(type);
        Map<String, Object> data = sanitize(raw, definition.fields());
        if (data.isEmpty()) throw new BizException("没有可更新的字段");
        List<String> columns = new ArrayList<>(data.keySet());
        jdbc.update("UPDATE " + definition.table() + " SET " + columns.stream().map(c -> c + "=?").collect(Collectors.joining(",")) + " WHERE id=?",
                append(columns.stream().map(data::get).toArray(), id));
    }

    private Map<String, Object> sanitize(Map<String, Object> raw, Set<String> allowed) {
        Map<String, Object> data = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String snake = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            if (allowed.contains(snake)) data.put(snake, value);
        });
        return data;
    }

    private Object[] append(Object[] values, Object last) {
        Object[] result = Arrays.copyOf(values, values.length + 1);
        result[values.length] = last;
        return result;
    }
}
