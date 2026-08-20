package com.medreport.report;

import com.medreport.common.BizException;
import com.medreport.file.FileAssetService;
import com.medreport.report.ReportModels.ExportResult;
import com.medreport.report.ReportModels.ReportContext;
import com.medreport.system.AlertService;
import com.medreport.system.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** v0.4 workflow facade.  It deliberately uses the existing package builder/exporters/senders. */
@Service
public class ReportGatewayService {
    private final JdbcTemplate jdbc; private final ReportPackageBuilder builder; private final List<ReportExporter> exporters;
    private final FileAssetService files; private final AuditService audit; private final AlertService alerts; private final ReportService legacy;
    public ReportGatewayService(JdbcTemplate jdbc, ReportPackageBuilder builder, List<ReportExporter> exporters, FileAssetService files,
                                AuditService audit, AlertService alerts, ReportService legacy) {
        this.jdbc=jdbc; this.builder=builder; this.exporters=exporters; this.files=files; this.audit=audit; this.alerts=alerts; this.legacy=legacy;
    }

    public List<Map<String,Object>> specs(){return jdbc.queryForList("SELECT * FROM report_spec ORDER BY spec_code,version");}
    public Map<String,Object> spec(long id){return one("SELECT * FROM report_spec WHERE id=?",id,"上报规范不存在");}
    @Transactional public long saveSpec(Map<String,Object> b, Long id){
        String code=req(b,"specCode"), version=req(b,"version"), name=req(b,"specName"); int demo=bool(b.getOrDefault("demo",true))?1:0;
        if(id==null){jdbc.update("INSERT INTO report_spec(spec_code,spec_name,authority_name,region_code,business_type,version,effective_from,effective_to,file_format,encoding,compression_type,file_naming_rule,transport_mode,default_frequency,demo,enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",code,name,b.get("authorityName"),b.get("regionCode"),b.getOrDefault("businessType","PATHOLOGY"),version,b.get("effectiveFrom"),b.get("effectiveTo"),b.getOrDefault("fileFormat","JSON"),b.getOrDefault("encoding","UTF-8"),b.getOrDefault("compressionType","NONE"),b.get("fileNamingRule"),b.getOrDefault("transportMode","HTTP"),b.get("defaultFrequency"),demo,bool(b.getOrDefault("enabled",true))?1:0); return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
        jdbc.update("UPDATE report_spec SET spec_code=?,spec_name=?,authority_name=?,region_code=?,business_type=?,version=?,effective_from=?,effective_to=?,file_format=?,encoding=?,compression_type=?,file_naming_rule=?,transport_mode=?,default_frequency=?,demo=?,enabled=? WHERE id=?",code,name,b.get("authorityName"),b.get("regionCode"),b.getOrDefault("businessType","PATHOLOGY"),version,b.get("effectiveFrom"),b.get("effectiveTo"),b.getOrDefault("fileFormat","JSON"),b.getOrDefault("encoding","UTF-8"),b.getOrDefault("compressionType","NONE"),b.get("fileNamingRule"),b.getOrDefault("transportMode","HTTP"),b.get("defaultFrequency"),demo,bool(b.getOrDefault("enabled",true))?1:0,id); return id;
    }
    public List<Map<String,Object>> fields(long specId){return jdbc.queryForList("SELECT * FROM report_spec_field WHERE spec_id=? ORDER BY sort_order,id",specId);}
    public long saveField(long specId, Map<String,Object> b){jdbc.update("INSERT INTO report_spec_field(spec_id,dataset_code,field_code,field_name,source_expression,data_type,required,max_length,dictionary_type,format_pattern,transform_type,default_value,sort_order,enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",specId,b.getOrDefault("datasetCode","PATIENT"),req(b,"fieldCode"),req(b,"fieldName"),b.get("sourceExpression"),b.getOrDefault("dataType","STRING"),bool(b.getOrDefault("required",false))?1:0,b.get("maxLength"),b.get("dictionaryType"),b.get("formatPattern"),b.get("transformType"),b.get("defaultValue"),b.getOrDefault("sortOrder",0),bool(b.getOrDefault("enabled",true))?1:0); return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}

    @Transactional public long createJob(Map<String,Object> b, String trigger){
        long specId=num(b.get("specId")); Map<String,Object> spec=spec(specId); Long planId=b.get("planId") instanceof Number n?n.longValue():null;
        LocalDateTime scheduled=b.get("scheduledAt") instanceof String s?LocalDateTime.parse(s):LocalDateTime.now(); int priority=Math.max(1,Math.min(9,((Number)b.getOrDefault("priority",5)).intValue()));
        String no="RJ"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        try { jdbc.update("INSERT INTO report_job(job_no,plan_id,spec_id,trigger_type,scheduled_at,priority,status) VALUES (?,?,?,?,?,?, 'WAITING')",no,planId,specId,trigger,scheduled,priority); }
        catch(Exception e){ if(planId!=null){return jdbc.queryForObject("SELECT id FROM report_job WHERE plan_id=? AND scheduled_at=?",Long.class,planId,scheduled);} throw e; }
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class); Object ids=b.get("caseIds");
        if(ids instanceof List<?> list) for(Object x:list) if(x instanceof Number n) jdbc.update("INSERT IGNORE INTO report_job_item(job_id,business_type,business_id,snapshot_version) VALUES (?,?,?,?)",id,spec.get("business_type"),n.longValue(),UUID.randomUUID().toString());
        return id;
    }
    public List<Map<String,Object>> jobs(){return jdbc.queryForList("SELECT * FROM report_job ORDER BY priority DESC,scheduled_at ASC,id ASC LIMIT 500");}
    public Map<String,Object> job(long id){return one("SELECT * FROM report_job WHERE id=?",id,"上报任务不存在");}
    public List<Map<String,Object>> prechecks(){return jdbc.queryForList("SELECT * FROM report_precheck ORDER BY id DESC LIMIT 300");}
    public List<Map<String,Object>> issues(long id){return jdbc.queryForList("SELECT * FROM report_precheck_issue WHERE precheck_id=? ORDER BY severity DESC,id",id);}

    @Transactional public long precheck(long jobId, String operator){
        Map<String,Object> job=job(jobId); jdbc.update("UPDATE report_job SET status='PRECHECKING',started_at=COALESCE(started_at,NOW()),attempt_count=attempt_count+1 WHERE id=? AND status IN ('WAITING','BLOCKED','FAILED')",jobId);
        long pc=insertPrecheck(jobId,((Number)job.get("spec_id")).longValue(),operator); int total=0,failed=0,warn=0;
        Map<String,Object> spec=spec(((Number)job.get("spec_id")).longValue()); List<Long> ids=jdbc.queryForList("SELECT business_id FROM report_job_item WHERE job_id=?",Long.class,jobId);
        List<Map<String,Object>> records;
        try { records=builder.build(String.valueOf(spec.get("business_type")),ids); } catch(Exception e){ issue(pc,null,null,"SYSTEM",null,e.getMessage(),"修正数据后重试","ERROR"); failed++; records=List.of(); }
        List<Map<String,Object>> rules=jdbc.queryForList("SELECT * FROM report_precheck_rule WHERE spec_id=? AND enabled=1",spec.get("id")); total=records.size();
        for(Map<String,Object> rec:records) for(Map<String,Object> rule:rules){Object val=value(rec,String.valueOf(rule.get("field_code"))); String type=String.valueOf(rule.get("rule_type")); boolean bad=satisfies(type,val,rule.get("rule_config")); if(!bad){String sev=String.valueOf(rule.get("severity")); if("WARNING".equals(sev))warn++;else failed++; issue(pc,rec.get("business_type"),rec.get("id"),String.valueOf(rule.get("field_code")),val,String.valueOf(rule.get("error_message")),String.valueOf(rule.get("suggestion")),sev);}}
        String status=failed>0?"BLOCKED":"PASSED"; jdbc.update("UPDATE report_precheck SET status=?,total_count=?,passed_count=?,failed_count=?,warning_count=?,finished_at=NOW() WHERE id=?",status,total,total-failed,failed,warn,pc); jdbc.update("UPDATE report_job SET status=? WHERE id=?",failed>0?"BLOCKED":"READY",jobId); if(failed>0) alerts.emit("REPORT_PRECHECK_BLOCKED","WARNING","REPORT_JOB",jobId,"上报预审核未通过，共"+failed+"个错误"); return pc;
    }
    @Transactional public long rerun(long precheckId,String operator){long jobId=jdbc.queryForObject("SELECT job_id FROM report_precheck WHERE id=?",Long.class,precheckId); return precheck(jobId,operator);}
    public void overrideIssue(long issueId,Map<String,Object> b,String operator){Map<String,Object> i=one("SELECT * FROM report_precheck_issue WHERE id=?",issueId,"问题不存在"); if(!"REPORT_PRECHECK_OVERRIDE".equals(String.valueOf(b.get("permission")))) throw new BizException("缺少 REPORT_PRECHECK_OVERRIDE 权限"); jdbc.update("UPDATE report_precheck_issue SET status='IGNORED',handled_by=?,handled_at=NOW() WHERE id=?",operator,issueId); jdbc.update("INSERT INTO report_data_override(job_id,batch_id,business_type,business_id,dataset_code,field_path,old_value,new_value,reason,operator) VALUES ((SELECT job_id FROM report_precheck WHERE id=?),NULL,?,?,?,?,?,?,?,?)",i.get("precheck_id"),i.get("business_type"),i.get("business_id"),i.get("dataset_code"),i.get("field_code"),String.valueOf(i.get("current_value")),b.get("newValue"),b.get("reason"),operator);}

    @Transactional public long generate(long jobId){Map<String,Object> job=job(jobId); if(!"READY".equals(job.get("status"))) throw new BizException("任务未通过预审核"); Map<String,Object> spec=spec(((Number)job.get("spec_id")).longValue()); List<Long> ids=jdbc.queryForList("SELECT business_id FROM report_job_item WHERE job_id=?",Long.class,jobId); List<Map<String,Object>> records=builder.build(String.valueOf(spec.get("business_type")),ids); String type=String.valueOf(spec.get("business_type")), format=String.valueOf(spec.get("file_format")); String batchNo="RP"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")); jdbc.update("INSERT INTO report_batch(batch_no,report_type,format,total_count,status,sender_type,endpoint,report_spec_id,report_job_id,precheck_status) VALUES (?,?,?,?,'GENERATING',?,?,?,?,'PASSED')",batchNo,type,format,records.size(),spec.get("transport_mode"),null,spec.get("id"),jobId); long batchId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class); for(Map<String,Object> r:records) jdbc.update("INSERT INTO report_record(batch_id,business_type,business_id,status) VALUES (?,?,?,'PENDING')",batchId,type,r.get("id")); ReportExporter ex=exporters.stream().filter(x->x.supports(format,type)).findFirst().orElseThrow(()->new BizException("不支持导出格式")); try{ExportResult out=ex.export(new ReportContext(batchNo,type,records,Map.of("specId",spec.get("id")))); files.registerGenerated(out.path(),type,batchId,"SYSTEM"); jdbc.update("UPDATE report_batch SET file_path=?,status='READY' WHERE id=?",out.path().toString(),batchId); jdbc.update("UPDATE report_job SET status='READY',batch_id=? WHERE id=?",batchId,jobId); return batchId;}catch(Exception e){jdbc.update("UPDATE report_batch SET status='FAILED',last_error=? WHERE id=?",e.getMessage(),batchId); jdbc.update("UPDATE report_job SET status='FAILED',error_message=?,finished_at=NOW() WHERE id=?",e.getMessage(),jobId); throw new BizException("生成失败: "+e.getMessage());}}
    public ReportModels.SendResult send(long jobId){Map<String,Object> j=job(jobId); if(!"READY".equals(j.get("status"))) throw new BizException("任务未准备好"); long batch=((Number)j.get("batch_id")).longValue(); Map<String,Object> b=legacy.find(batch); if(!"PASSED".equals(String.valueOf(b.get("precheck_status")))) throw new BizException("当前批次预审核未通过，禁止上报"); ReportModels.SendResult r=legacy.send(batch,true); jdbc.update("UPDATE report_job SET status=?,finished_at=IF(?='SUCCESS',NOW(),finished_at),result_message=? WHERE id=?",r.success()?"SUCCESS":"FAILED",r.success()?"SUCCESS":"FAILED",r.message(),jobId); return r;}
    @Scheduled(fixedDelay=30000,initialDelay=15000) public void runQueue(){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM report_job WHERE status='WAITING' AND scheduled_at<=NOW() ORDER BY priority DESC,scheduled_at ASC,id ASC LIMIT 10"); for(Map<String,Object> j:rows){long id=((Number)j.get("id")).longValue(); try{precheck(id,"SYSTEM"); if("READY".equals(job(id).get("status"))){generate(id); send(id);}}catch(Exception e){jdbc.update("UPDATE report_job SET status='FAILED',error_message=?,finished_at=NOW() WHERE id=?",e.getMessage(),id);}}}

    private long insertPrecheck(long job,long spec,String op){jdbc.update("INSERT INTO report_precheck(job_id,spec_id,status,started_at,operator) VALUES (?,?,'RUNNING',NOW(),?)",job,spec,op); return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
    private void issue(long pc,Object bt,Object bid,String field,Object val,String msg,Object suggestion,String sev){jdbc.update("INSERT INTO report_precheck_issue(precheck_id,business_type,business_id,field_code,current_value,error_message,suggestion,severity) VALUES (?,?,?,?,?,?,?,?)",pc,bt,bid,field,val==null?null:String.valueOf(val),msg,suggestion,sev);}
    private Object value(Map<String,Object> r,String field){if(r.containsKey(field))return r.get(field); return r.get(field.toLowerCase(Locale.ROOT));}
    private boolean satisfies(String t,Object v,Object cfg){String s=v==null?"":String.valueOf(v); return switch(t){case "REQUIRED","NOT_NULL"->!s.isBlank();case "REGEX"->s.matches(String.valueOf(cfg));case "ENUM","DICTIONARY"->Arrays.asList(String.valueOf(cfg).split(",")).contains(s);case "LENGTH"->cfg==null||s.length()<=Integer.parseInt(String.valueOf(cfg));case "RANGE"->{String[] p=String.valueOf(cfg).split(",");double n=Double.parseDouble(s);yield n>=Double.parseDouble(p[0])&&n<=Double.parseDouble(p[1]);}default->true;};}
    private Map<String,Object> one(String sql,Object p,String msg){List<Map<String,Object>> r=jdbc.queryForList(sql,p);if(r.isEmpty())throw new BizException(msg);return r.getFirst();}
    private String req(Map<String,Object>b,String k){Object v=b.get(k);if(v==null||String.valueOf(v).isBlank())throw new BizException(k+"不能为空");return String.valueOf(v);}
    private long num(Object v){if(!(v instanceof Number n))throw new BizException("specId不能为空");return n.longValue();}
    private boolean bool(Object v){return v instanceof Boolean b?b:v instanceof Number n?n.intValue()!=0:Boolean.parseBoolean(String.valueOf(v));}
}
