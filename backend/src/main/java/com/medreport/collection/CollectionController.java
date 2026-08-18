package com.medreport.collection;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CollectionController {
    private final JdbcTemplate jdbc;
    private final CollectionService collectionService;

    public CollectionController(JdbcTemplate jdbc, CollectionService collectionService) {
        this.jdbc = jdbc;
        this.collectionService = collectionService;
    }

    @GetMapping("/collect-tasks")
    @RequirePermission("DATA_VIEW")
    public ApiResponse<List<Map<String, Object>>> tasks() {
        return ApiResponse.ok(jdbc.queryForList("""
                SELECT t.*,d.name data_source_name FROM collect_task t JOIN data_source_config d ON d.id=t.data_source_id ORDER BY t.id DESC
                """));
    }

    @PostMapping("/collect-tasks")
    @RequirePermission("DATA_EDIT")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        jdbc.update("""
                INSERT INTO collect_task(task_name,data_source_id,business_type,execution_expression,execution_content,incremental_field,last_sync_time,enabled,next_run_time)
                VALUES (?,?,?,?,?,?,?, ?,NOW())
                """, body.get("taskName"), body.get("dataSourceId"), body.get("businessType"), body.getOrDefault("executionExpression", "30s"),
                body.get("executionContent"), body.getOrDefault("incrementalField", "update_time"), body.getOrDefault("lastSyncTime", "1970-01-01 00:00:00"), bool(body.getOrDefault("enabled", true)));
        return ApiResponse.ok(Map.of("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    @PutMapping("/collect-tasks/{id}")
    @RequirePermission("DATA_EDIT")
    public ApiResponse<Void> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        int count = jdbc.update("""
                UPDATE collect_task SET task_name=?,data_source_id=?,business_type=?,execution_expression=?,execution_content=?,incremental_field=?,enabled=? WHERE id=?
                """, body.get("taskName"), body.get("dataSourceId"), body.get("businessType"), body.getOrDefault("executionExpression", "30s"),
                body.get("executionContent"), body.getOrDefault("incrementalField", "update_time"), bool(body.getOrDefault("enabled", true)), id);
        if (count == 0) throw new BizException("采集任务不存在");
        return ApiResponse.ok();
    }

    @PostMapping("/collect-tasks/{id}/execute")
    @RequirePermission("DATA_EDIT")
    public ApiResponse<Map<String, Object>> execute(@PathVariable long id) {
        return ApiResponse.ok(collectionService.execute(id));
    }

    @GetMapping("/collect-logs")
    @RequirePermission("DATA_VIEW")
    public ApiResponse<List<Map<String, Object>>> logs() {
        return ApiResponse.ok(jdbc.queryForList("""
                SELECT l.*,t.task_name FROM collect_log l JOIN collect_task t ON t.id=l.task_id ORDER BY l.id DESC LIMIT 200
                """));
    }

    private boolean bool(Object value) { return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue()!=0 : Boolean.parseBoolean(String.valueOf(value)); }
}
