package com.medreport.report;

import com.medreport.common.BizException;
import com.medreport.file.FileAssetService;
import com.medreport.report.ReportModels.*;
import com.medreport.system.AlertService;
import com.medreport.system.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ReportService {
    private final JdbcTemplate jdbc;
    private final List<ReportExporter> exporters;
    private final List<ReportSender> senders;
    private final AuditService audit;
    private final ReportPackageBuilder packageBuilder;
    private final FileAssetService files;
    private final AlertService alerts;
    private final String defaultEndpoint;

    public ReportService(JdbcTemplate jdbc, List<ReportExporter> exporters, List<ReportSender> senders, AuditService audit,
                         ReportPackageBuilder packageBuilder, FileAssetService files, AlertService alerts,
                         @Value("${app.mock-report-url}") String defaultEndpoint) {
        this.jdbc = jdbc;
        this.exporters = exporters;
        this.senders = senders;
        this.audit = audit;
        this.packageBuilder = packageBuilder;
        this.files = files;
        this.alerts = alerts;
        this.defaultEndpoint = defaultEndpoint;
    }

    @Transactional(noRollbackFor=BizException.class)
    public long generate(String reportType, String format, String senderType, String endpoint) {
        return generate(reportType,format,senderType,endpoint,List.of(),null);
    }

    @Transactional(noRollbackFor=BizException.class)
    public long generate(String reportType,String format,String senderType,String endpoint,List<Long> selectedIds,Long templateId) {
        Map<String,Object> template=templateId==null?Map.of():template(templateId);
        String type=upper(template.getOrDefault("report_type",reportType==null?"PATIENT":reportType));
        String exportFormat=upper(template.getOrDefault("format",format==null?"JSON":format));
        String sendType=upper(template.getOrDefault("sender_type",senderType==null?"HTTP":senderType));
        String sendEndpoint=template.isEmpty()?endpoint:(template.get("endpoint")==null?endpoint:String.valueOf(template.get("endpoint")));
        if(bool(template.get("include_slide"))&&"PATHOLOGY".equals(type))type="PATHOLOGY_PACKAGE";
        List<Map<String,Object>> records=packageBuilder.build(type,selectedIds);
        String batchNo = "RP" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        jdbc.update("""
                INSERT INTO report_batch(batch_no,template_id,report_type,format,total_count,status,sender_type,endpoint)
                VALUES (?,?,?,?,?,?,?,?)
                """,batchNo,templateId,type,exportFormat,records.size(),"GENERATING",sendType,sendEndpoint);
        long batchId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (Map<String, Object> record : records) {
            jdbc.update("INSERT INTO report_record(batch_id,business_type,business_id,status) VALUES (?,?,?,'PENDING')", batchId, type, record.get("id"));
        }
        String finalType=type;
        ReportExporter exporter=exporters.stream().filter(item->item.supports(exportFormat,finalType)).findFirst()
                .orElseThrow(()->new BizException("不支持的导出格式: "+exportFormat+" / "+finalType));
        try {
            ExportResult result=exporter.export(new ReportContext(batchNo,type,records,Map.of("templateId",templateId==null?0:templateId)));
            files.registerGenerated(result.path(),type,batchId,"SYSTEM");
            jdbc.update("UPDATE report_batch SET file_path=?,status='READY' WHERE id=?", result.path().toString(), batchId);
            audit.log("SYSTEM","生成上报","数据上报",batchId,"SUCCESS",exportFormat+", "+records.size()+" 条");
            return batchId;
        } catch (Exception ex) {
            jdbc.update("UPDATE report_batch SET status='FAILED',last_error=? WHERE id=?", ex.getMessage(), batchId);
            throw new BizException(ex.getMessage());
        }
    }

    public SendResult send(long batchId, boolean manual) {
        Map<String, Object> batch = find(batchId);
        String status = String.valueOf(batch.get("status"));
        if (!Set.of("READY", "FAILED").contains(status)) throw new BizException("当前批次状态不允许上报");
        if (batch.get("report_job_id") != null && !"PASSED".equals(String.valueOf(batch.get("precheck_status")))) {
            throw new BizException("当前批次预审核未通过，禁止上报");
        }
        String senderType = String.valueOf(batch.get("sender_type"));
        ReportSender sender = senders.stream().filter(item -> item.supports(senderType)).findFirst().orElseThrow(() -> new BizException("不支持的发送方式: " + senderType));
        String configuredEndpoint = batch.get("endpoint") == null ? null : String.valueOf(batch.get("endpoint"));
        String endpoint = configuredEndpoint == null || configuredEndpoint.isBlank()
                ? ("HTTP".equalsIgnoreCase(senderType) ? defaultEndpoint : null)
                : configuredEndpoint;
        jdbc.update("UPDATE report_batch SET status='REPORTING',next_retry_time=NULL WHERE id=?", batchId);
        SendResult result = sender.send(new ReportPackage(batchId, Path.of(String.valueOf(batch.get("file_path"))), endpoint, senderType));
        if (result.success()) {
            jdbc.update("UPDATE report_batch SET status='SUCCESS',success_count=total_count,failed_count=0,reported_at=NOW(),last_error=NULL WHERE id=?", batchId);
            jdbc.update("UPDATE report_record SET status='SUCCESS',error_message=NULL WHERE batch_id=?", batchId);
        } else {
            int retries=((Number)batch.get("retry_count")).intValue()+1;
            LocalDateTime next=retries>=4?null:LocalDateTime.now().plusMinutes(switch(retries){case 1->1;case 2->5;default->30;});
            jdbc.update("UPDATE report_batch SET status='FAILED',failed_count=total_count,retry_count=?,next_retry_time=?,last_error=? WHERE id=?",
                    retries, next, result.message(), batchId);
            jdbc.update("UPDATE report_record SET status='FAILED',error_message=? WHERE batch_id=?", result.message(), batchId);
            alerts.emit("REPORT_FAILED",retries>=4?"CRITICAL":"WARNING","REPORT",batchId,
                    "上报失败（第 "+retries+" 次）: "+result.message());
        }
        audit.log(manual ? "ADMIN" : "SYSTEM", manual ? "人工重新上报" : "自动上报", "数据上报", batchId, result.success() ? "SUCCESS" : "FAILED", result.message());
        return result;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void retryFailed() {
        List<Map<String, Object>> batches = jdbc.queryForList("SELECT id FROM report_batch WHERE status='FAILED' AND retry_count<4 AND next_retry_time IS NOT NULL AND next_retry_time<=NOW()");
        for (Map<String, Object> batch : batches) {
            try { send(((Number) batch.get("id")).longValue(), false); } catch (Exception ignored) { }
        }
    }

    public Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM report_batch WHERE id=?", id);
        if (rows.isEmpty()) throw new BizException("上报批次不存在");
        return rows.getFirst();
    }

    public List<Map<String,Object>> pendingCases(){return packageBuilder.pendingCases();}

    private Map<String,Object> template(long id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM report_template WHERE id=? AND enabled=1",id);
        if(rows.isEmpty())throw new BizException("上报模板不存在或已停用");return rows.getFirst();}
    private String upper(Object value){return String.valueOf(value).toUpperCase(Locale.ROOT);}
    private boolean bool(Object value){return value instanceof Boolean b?b:value instanceof Number n?n.intValue()!=0:Boolean.parseBoolean(String.valueOf(value));}
}
