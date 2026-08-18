package com.medreport.system;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AlertService {
    private final JdbcTemplate jdbc;private final SystemMetricsService metrics;
    public AlertService(JdbcTemplate jdbc,SystemMetricsService metrics){this.jdbc=jdbc;this.metrics=metrics;}

    public void emit(String type,String severity,String sourceType,Long sourceId,String message){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE event_type=? AND source_type=? AND (source_id=? OR (source_id IS NULL AND ? IS NULL)) AND status IN ('OPEN','ACKNOWLEDGED')",Integer.class,type,sourceType,sourceId,sourceId);
        if(count==null||count==0)jdbc.update("INSERT INTO alert_event(event_type,severity,source_type,source_id,message,status) VALUES (?,?,?,?,?,'OPEN')",type,severity,sourceType,sourceId,message);
    }

    @Scheduled(fixedDelay=30000,initialDelay=30000)
    @SuppressWarnings("unchecked")
    public void evaluate(){
        Map<String,Object> all=metrics.metrics();Map<String,Object> disk=(Map<String,Object>)all.get("disk");Object usage=disk.get("usagePercent");
        for(Map<String,Object> rule:jdbc.queryForList("SELECT * FROM alert_rule WHERE enabled=1")){
            String type=String.valueOf(rule.get("rule_type"));double threshold=rule.get("threshold_value") instanceof Number n?n.doubleValue():90;
            if("DISK_USAGE".equals(type)&&usage instanceof Number n&&n.doubleValue()>=threshold)emit(type,String.valueOf(rule.get("severity")),"SYSTEM",null,"磁盘使用率达到 "+n.doubleValue()+"%");
        }
        failures("archive_task","ARCHIVE_FAILED","ARCHIVE");failures("backup_task","BACKUP_FAILED","BACKUP");
        failures("slide_file","SLIDE_PARSE_FAILED","SLIDE");failures("report_batch","REPORT_FAILED","REPORT");
        int collect=jdbc.queryForObject("SELECT COUNT(*) FROM collect_log WHERE status='FAILED' AND created_at>=DATE_SUB(NOW(),INTERVAL 1 HOUR)",Integer.class);
        if(collect>=3)emit("COLLECT_CONSECUTIVE_FAILED","CRITICAL","COLLECT",null,"最近一小时采集连续失败 "+collect+" 次");
    }

    private void failures(String table,String type,String source){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id FROM "+table+" WHERE status='FAILED' AND updated_at>=DATE_SUB(NOW(),INTERVAL 1 HOUR) ORDER BY id DESC LIMIT 20");
        for(Map<String,Object> row:rows)emit(type,"WARNING",source,((Number)row.get("id")).longValue(),source+" 任务失败");
    }
}
