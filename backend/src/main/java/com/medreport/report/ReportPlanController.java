package com.medreport.report;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import com.medreport.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/report-plans")
@RequirePermission("REPORT_GENERATE")
public class ReportPlanController {
    private final ReportPlanService plans;private final AuditService audit;
    public ReportPlanController(ReportPlanService plans,AuditService audit){this.plans=plans;this.audit=audit;}
    @GetMapping public ApiResponse<List<Map<String,Object>>> list(){return ApiResponse.ok(plans.list());}
    @PostMapping public ApiResponse<Map<String,Object>> create(@RequestBody Map<String,Object> body,HttpServletRequest request){long id=plans.save(null,body);audit.log(request,"新增上报计划","数据上报",id,"SUCCESS",String.valueOf(body.get("name")));return ApiResponse.ok(Map.of("id",id));}
    @PutMapping("/{id}") public ApiResponse<Void> update(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){plans.save(id,body);audit.log(request,"修改上报计划","数据上报",id,"SUCCESS",String.valueOf(body.get("name")));return ApiResponse.ok();}
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable long id,HttpServletRequest request){plans.delete(id);audit.log(request,"删除上报计划","数据上报",id,"SUCCESS",null);return ApiResponse.ok();}
    @PostMapping("/{id}/run") @RequirePermission("REPORT_SEND") public ApiResponse<Map<String,Object>> run(@PathVariable long id,HttpServletRequest request){long batchId=plans.runNow(id);audit.log(request,"执行上报计划","数据上报",id,"SUCCESS",String.valueOf(batchId));return ApiResponse.ok(Map.of("batchId",batchId));}
}
