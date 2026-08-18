package com.medreport.backup;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import com.medreport.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/backups")
@RequirePermission("FILE_MANAGE")
public class BackupController {
    private final JdbcTemplate jdbc;private final BackupService backups;private final AuditService audit;
    public BackupController(JdbcTemplate jdbc,BackupService backups,AuditService audit){this.jdbc=jdbc;this.backups=backups;this.audit=audit;}
    @GetMapping("/tasks")public ApiResponse<List<Map<String,Object>>> tasks(){return ApiResponse.ok(jdbc.queryForList("""
            SELECT b.*,f.display_name,t.name target_name FROM backup_task b JOIN file_asset f ON f.id=b.file_id
            JOIN storage_target t ON t.id=b.target_storage_id ORDER BY b.id DESC LIMIT 500
            """));}
    @PostMapping("/files/{fileId}")public ApiResponse<Map<String,Object>> backup(@PathVariable long fileId,@RequestBody(required=false)Map<String,Object> body,HttpServletRequest request){
        Long target=body!=null&&body.get("targetStorageId") instanceof Number n?n.longValue():null;Map<String,Object> task=backups.backup(fileId,target);
        audit.log(request,"手动备份文件","文件管理",fileId,"SUCCESS",String.valueOf(task.get("id")));return ApiResponse.ok(task);}
    @GetMapping("/policies")public ApiResponse<List<Map<String,Object>>> policies(){return ApiResponse.ok(jdbc.queryForList("SELECT * FROM backup_policy ORDER BY id DESC"));}
    @PostMapping("/policies")public ApiResponse<Map<String,Object>> policy(@RequestBody Map<String,Object> body){
        jdbc.update("INSERT INTO backup_policy(name,source_storage_id,target_storage_id,frequency,cron_expression,enabled) VALUES (?,?,?,?,?,?)",
                body.get("name"),body.get("sourceStorageId"),body.get("targetStorageId"),body.getOrDefault("frequency","MANUAL"),body.get("cronExpression"),body.getOrDefault("enabled",true));
        return ApiResponse.ok(Map.of("id",jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class)));}
}
