package com.medreport.report;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/report-specs")
@RequirePermission("REPORT_GENERATE")
public class ReportGatewayController {
    private final ReportGatewayService gateway; private final JdbcTemplate jdbc;
    public ReportGatewayController(ReportGatewayService gateway, JdbcTemplate jdbc){this.gateway=gateway;this.jdbc=jdbc;}
    @GetMapping public ApiResponse<List<Map<String,Object>>> list(){return ApiResponse.ok(gateway.specs());}
    @PostMapping public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> b){long id=gateway.saveSpec(b,null);return ApiResponse.ok(gateway.spec(id));}
    @PutMapping("/{id}") public ApiResponse<Map<String,Object>> update(@PathVariable long id,@RequestBody Map<String,Object> b){gateway.saveSpec(b,id);return ApiResponse.ok(gateway.spec(id));}
    @GetMapping("/{id}/fields") public ApiResponse<List<Map<String,Object>>> fields(@PathVariable long id){return ApiResponse.ok(gateway.fields(id));}
    @PostMapping("/{id}/fields") public ApiResponse<Map<String,Object>> field(@PathVariable long id,@RequestBody Map<String,Object>b){long f=gateway.saveField(id,b);return ApiResponse.ok(Map.of("id",f));}
}

@RestController
@RequestMapping("/api/report-jobs")
@RequirePermission("REPORT_GENERATE")
class ReportJobController {
    private final ReportGatewayService gateway;
    ReportJobController(ReportGatewayService gateway){this.gateway=gateway;}
    @GetMapping public ApiResponse<List<Map<String,Object>>> list(){return ApiResponse.ok(gateway.jobs());}
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> get(@PathVariable long id){return ApiResponse.ok(gateway.job(id));}
    @PostMapping public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object>b){long id=gateway.createJob(b,"MANUAL");return ApiResponse.ok(gateway.job(id));}
    @PostMapping("/{id}/precheck") public ApiResponse<Map<String,Object>> precheck(@PathVariable long id,HttpServletRequest r){return ApiResponse.ok(Map.of("id",gateway.precheck(id,user(r))));}
    @PostMapping("/{id}/generate") public ApiResponse<Map<String,Object>> generate(@PathVariable long id){long batch=gateway.generate(id);return ApiResponse.ok(Map.of("batchId",batch,"job",gateway.job(id)));}
    @PostMapping("/{id}/send") @RequirePermission("REPORT_SEND") public ApiResponse<ReportModels.SendResult> send(@PathVariable long id){return ApiResponse.ok(gateway.send(id));}
    private String user(HttpServletRequest r){String u=r.getHeader("X-User");return u==null?"SYSTEM":u;}
}

@RestController
@RequestMapping("/api/report-prechecks")
@RequirePermission({"DATA_VIEW","REPORT_GENERATE"})
class ReportPrecheckController {
    private final ReportGatewayService gateway; private final JdbcTemplate jdbc;
    ReportPrecheckController(ReportGatewayService gateway,JdbcTemplate jdbc){this.gateway=gateway;this.jdbc=jdbc;}
    @GetMapping public ApiResponse<List<Map<String,Object>>> list(){return ApiResponse.ok(gateway.prechecks());}
    @GetMapping("/{id}/issues") public ApiResponse<List<Map<String,Object>>> issues(@PathVariable long id){return ApiResponse.ok(gateway.issues(id));}
    @PostMapping("/{id}/rerun") public ApiResponse<Map<String,Object>> rerun(@PathVariable long id,HttpServletRequest r){return ApiResponse.ok(Map.of("id",gateway.rerun(id,user(r))));}
    @PostMapping("/{id}/issues/{issueId}/ignore") @RequirePermission("REPORT_PRECHECK_OVERRIDE") public ApiResponse<Void> ignore(@PathVariable long id,@PathVariable long issueId,@RequestBody Map<String,Object>b,HttpServletRequest r){b.put("permission","REPORT_PRECHECK_OVERRIDE");gateway.overrideIssue(issueId,b,user(r));return ApiResponse.ok(null);}
    private String user(HttpServletRequest r){String u=r.getHeader("X-User");return u==null?"SYSTEM":u;}
}
