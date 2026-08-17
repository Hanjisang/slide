package com.medreport.report;

import com.medreport.common.BizException;
import com.medreport.report.ReportModels.*;
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
    private final String defaultEndpoint;

    public ReportService(JdbcTemplate jdbc, List<ReportExporter> exporters, List<ReportSender> senders, AuditService audit,
                         @Value("${app.mock-report-url}") String defaultEndpoint) {
        this.jdbc = jdbc;
        this.exporters = exporters;
        this.senders = senders;
        this.audit = audit;
        this.defaultEndpoint = defaultEndpoint;
    }

    @Transactional
    public long generate(String reportType, String format, String senderType, String endpoint) {
        String type = reportType == null ? "PATIENT" : reportType.toUpperCase(Locale.ROOT);
        if (!"PATIENT".equals(type)) throw new BizException("MVP 当前仅开放患者标准数据上报");
        List<Map<String, Object>> records = jdbc.queryForList("""
                SELECT p.* FROM patient p WHERE p.quality_status='PASSED'
                AND NOT EXISTS (SELECT 1 FROM validation_error e WHERE e.business_type='PATIENT' AND e.business_id=p.id AND e.status IN ('PENDING','FAILED'))
                ORDER BY p.id
                """);
        if (records.isEmpty()) throw new BizException("没有符合质量要求的待上报数据");
        String batchNo = "RP" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        jdbc.update("""
                INSERT INTO report_batch(batch_no,report_type,format,total_count,status,sender_type,endpoint)
                VALUES (?,?,?,?,?,?,?)
                """, batchNo, type, format.toUpperCase(Locale.ROOT), records.size(), "GENERATING", senderType.toUpperCase(Locale.ROOT), endpoint);
        long batchId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (Map<String, Object> record : records) {
            jdbc.update("INSERT INTO report_record(batch_id,business_type,business_id,status) VALUES (?,?,?,'PENDING')", batchId, type, record.get("id"));
        }
        ReportExporter exporter = exporters.stream().filter(item -> item.supports(format)).findFirst().orElseThrow(() -> new BizException("不支持的导出格式: " + format));
        try {
            ExportResult result = exporter.export(new ReportContext(batchNo, type, records));
            jdbc.update("UPDATE report_batch SET file_path=?,status='READY' WHERE id=?", result.path().toString(), batchId);
            audit.log("SYSTEM", "生成上报", "数据上报", batchId, "SUCCESS", format + ", " + records.size() + " 条");
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
            int retries = ((Number) batch.get("retry_count")).intValue() + 1;
            LocalDateTime next = retries >= 3 ? null : LocalDateTime.now().plusMinutes(switch (retries) { case 1 -> 1; case 2 -> 5; default -> 30; });
            jdbc.update("UPDATE report_batch SET status='FAILED',failed_count=total_count,retry_count=?,next_retry_time=?,last_error=? WHERE id=?",
                    retries, next, result.message(), batchId);
            jdbc.update("UPDATE report_record SET status='FAILED',error_message=? WHERE batch_id=?", result.message(), batchId);
        }
        audit.log(manual ? "ADMIN" : "SYSTEM", manual ? "人工重新上报" : "自动上报", "数据上报", batchId, result.success() ? "SUCCESS" : "FAILED", result.message());
        return result;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void retryFailed() {
        List<Map<String, Object>> batches = jdbc.queryForList("SELECT id FROM report_batch WHERE status='FAILED' AND retry_count<3 AND next_retry_time IS NOT NULL AND next_retry_time<=NOW()");
        for (Map<String, Object> batch : batches) {
            try { send(((Number) batch.get("id")).longValue(), false); } catch (Exception ignored) { }
        }
    }

    public Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM report_batch WHERE id=?", id);
        if (rows.isEmpty()) throw new BizException("上报批次不存在");
        return rows.getFirst();
    }
}
