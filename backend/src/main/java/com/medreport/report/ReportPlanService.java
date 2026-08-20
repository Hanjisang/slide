package com.medreport.report;

import com.medreport.common.BizException;
import com.medreport.system.AlertService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ReportPlanService {
    private final JdbcTemplate jdbc;
    private final ReportService reports;
    private final AlertService alerts;
    private final ReportGatewayService gateway;

    public ReportPlanService(JdbcTemplate jdbc,ReportService reports,AlertService alerts,ReportGatewayService gateway){
        this.jdbc=jdbc;this.reports=reports;this.alerts=alerts;this.gateway=gateway;
    }

    public List<Map<String,Object>> list(){return jdbc.queryForList("""
            SELECT p.*,t.name template_name,t.report_type,t.format,t.sender_type
            FROM report_plan p JOIN report_template t ON t.id=p.template_id ORDER BY p.id DESC
            """);}

    public long save(Long id,Map<String,Object> body){
        String name=text(body,"name");long templateId=number(body,"templateId");
        String frequency=text(body,"frequencyType").toUpperCase(Locale.ROOT);
        if(!List.of("MANUAL","HOURLY","DAILY","WEEKLY","MONTHLY","CRON").contains(frequency))throw new BizException("frequencyType 无效");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM report_template WHERE id=?",Integer.class,templateId)==0)throw new BizException("上报模板不存在");
        String cron=body.get("cronExpression")==null?null:String.valueOf(body.get("cronExpression")).trim();
        if("CRON".equals(frequency)){if(cron==null||cron.isBlank())throw new BizException("CRON 计划必须填写 cronExpression");parseCron(cron);}
        boolean enabled=bool(body.getOrDefault("enabled",true));LocalDateTime next=enabled?next(frequency,cron,LocalDateTime.now()):null;
        if(id==null){
            jdbc.update("INSERT INTO report_plan(name,template_id,spec_id,frequency_type,cron_expression,priority,max_retry,retry_policy,execution_timeout_minutes,concurrency_policy,enabled,next_run_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    name,templateId,body.get("specId"),frequency,cron,priority(body),body.getOrDefault("maxRetry",4),body.getOrDefault("retryPolicy","FIXED"),body.getOrDefault("executionTimeoutMinutes",60),body.getOrDefault("concurrencyPolicy","QUEUE"),enabled,next);
            return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        }
        if(jdbc.update("UPDATE report_plan SET name=?,template_id=?,spec_id=?,frequency_type=?,cron_expression=?,priority=?,max_retry=?,retry_policy=?,execution_timeout_minutes=?,concurrency_policy=?,enabled=?,next_run_time=? WHERE id=?",
                name,templateId,body.get("specId"),frequency,cron,priority(body),body.getOrDefault("maxRetry",4),body.getOrDefault("retryPolicy","FIXED"),body.getOrDefault("executionTimeoutMinutes",60),body.getOrDefault("concurrencyPolicy","QUEUE"),enabled,next,id)==0)throw new BizException("上报计划不存在");
        return id;
    }

    public void delete(long id){if(jdbc.update("DELETE FROM report_plan WHERE id=?",id)==0)throw new BizException("上报计划不存在");}

    public long runNow(long id){Map<String,Object> plan=find(id);return execute(plan);}

    @Scheduled(fixedDelay=30000,initialDelay=30000)
    public void runDue(){
        for(Map<String,Object> plan:jdbc.queryForList("SELECT * FROM report_plan WHERE enabled=1 AND next_run_time IS NOT NULL AND next_run_time<=NOW() ORDER BY id")){
            try{execute(plan);}catch(Exception ex){alerts.emit("REPORT_PLAN_FAILED","WARNING","REPORT_PLAN",id(plan),ex.getMessage());advance(plan);}
        }
    }

    private long execute(Map<String,Object> plan){
        if(plan.get("spec_id")!=null){Map<String,Object> b=new java.util.HashMap<>();b.put("specId",plan.get("spec_id"));b.put("planId",plan.get("id"));b.put("scheduledAt",LocalDateTime.now().toString());b.put("priority",plan.getOrDefault("priority",5));long job=gateway.createJob(b,"SCHEDULE");gateway.precheck(job,"SYSTEM");if("READY".equals(gateway.job(job).get("status"))){gateway.generate(job);gateway.send(job);}advance(plan);return job;}
        long batchId=reports.generate(null,null,null,null,List.of(),((Number)plan.get("template_id")).longValue());
        reports.send(batchId,false);advance(plan);return batchId;
    }

    private void advance(Map<String,Object> plan){String frequency=String.valueOf(plan.get("frequency_type"));String cron=plan.get("cron_expression")==null?null:String.valueOf(plan.get("cron_expression"));
        jdbc.update("UPDATE report_plan SET last_run_time=NOW(),next_run_time=? WHERE id=?",next(frequency,cron,LocalDateTime.now()),id(plan));}
    private Map<String,Object> find(long id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM report_plan WHERE id=?",id);
        if(rows.isEmpty())throw new BizException("上报计划不存在");return rows.getFirst();}
    private LocalDateTime next(String frequency,String cron,LocalDateTime now){return switch(frequency){
        case "MANUAL"->null;case "HOURLY"->now.plusHours(1).withMinute(0).withSecond(0).withNano(0);
        case "DAILY"->now.toLocalDate().plusDays(1).atStartOfDay();case "WEEKLY"->now.toLocalDate().plusWeeks(1).atStartOfDay();case "MONTHLY"->now.toLocalDate().plusMonths(1).atStartOfDay();case "CRON"->parseCron(cron).next(now);default->throw new BizException("frequencyType 无效");};}
    private CronExpression parseCron(String value){try{return CronExpression.parse(value);}catch(IllegalArgumentException ex){throw new BizException("Cron 表达式无效: "+ex.getMessage());}}
    private long id(Map<String,Object> row){return ((Number)row.get("id")).longValue();}
    private String text(Map<String,Object> body,String key){String value=body.get(key)==null?"":String.valueOf(body.get(key)).trim();if(value.isEmpty())throw new BizException(key+" 不能为空");return value;}
    private long number(Map<String,Object> body,String key){Object value=body.get(key);if(value instanceof Number n)return n.longValue();try{return Long.parseLong(String.valueOf(value));}catch(Exception ex){throw new BizException(key+" 无效");}}
    private boolean bool(Object value){return value instanceof Boolean b?b:value instanceof Number n?n.intValue()!=0:Boolean.parseBoolean(String.valueOf(value));}
    private int priority(Map<String,Object>b){Object v=b.getOrDefault("priority",5);return Math.max(1,Math.min(9,v instanceof Number n?n.intValue():Integer.parseInt(String.valueOf(v))));}
}
