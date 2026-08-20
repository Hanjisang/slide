package com.medreport.report;

import com.medreport.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ReportPackageBuilder {
    private final JdbcTemplate jdbc;
    public ReportPackageBuilder(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public List<Map<String,Object>> build(String reportType,List<Long> selectedIds){
        String type=reportType.toUpperCase(Locale.ROOT);
        if("PATIENT".equals(type))return patients();
        if(!Set.of("PATHOLOGY","PATHOLOGY_PACKAGE").contains(type))throw new BizException("不支持的上报业务类型: "+type);
        List<Long> ids=selectedIds==null?List.of():selectedIds;
        String sql="SELECT c.*,p.quality_status,p.name patient_name FROM pathology_case c LEFT JOIN patient p ON p.id=c.patient_id";
        List<Map<String,Object>> cases;
        if(ids.isEmpty())cases=jdbc.queryForList(sql+" ORDER BY c.id");
        else{String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));cases=jdbc.queryForList(sql+" WHERE c.id IN ("+placeholders+") ORDER BY c.id",ids.toArray());}
        if(cases.isEmpty())throw new BizException("没有选择可上报的病理病例");
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> pathology:cases){long caseId=((Number)pathology.get("id")).longValue();Long patientId=pathology.get("patient_id") instanceof Number n?n.longValue():null;
            if(patientId!=null&&!"PASSED".equals(pathology.get("quality_status")))throw new BizException("病例 "+pathology.get("pathology_no")+" 的患者质量状态未通过");
            int errors=jdbc.queryForObject("SELECT COUNT(*) FROM validation_error WHERE ((business_type='PATHOLOGY_CASE' AND business_id=?) OR (business_type='PATIENT' AND business_id=?)) AND status IN ('PENDING','FAILED')",Integer.class,caseId,patientId);
            if(errors>0)throw new BizException("病例 "+pathology.get("pathology_no")+" 存在未处理质量异常");
            List<Map<String,Object>> slides=jdbc.queryForList("SELECT * FROM slide_file WHERE case_id=? AND deleted=0 AND status IN ('READY','ARCHIVED') ORDER BY id",caseId);
            if("PATHOLOGY_PACKAGE".equals(type)&&slides.isEmpty())throw new BizException("病例 "+pathology.get("pathology_no")+" 没有 READY 切片");
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",caseId);item.put("pathology",pathology);item.put("slides",slides);
            item.put("patient",patientId==null?Map.of():one("SELECT * FROM patient WHERE id=?",patientId));
            item.put("visits",patientId==null?List.of():jdbc.queryForList("SELECT * FROM visit WHERE patient_id=? ORDER BY id",patientId));
            item.put("diagnoses",patientId==null?List.of():jdbc.queryForList("SELECT * FROM diagnosis WHERE patient_id=? ORDER BY id",patientId));result.add(item);}
        return result;
    }

    public List<Map<String,Object>> pendingCases(){return jdbc.queryForList("""
            SELECT c.id,c.pathology_no,c.specimen_name,c.specimen_type_code,c.pathology_diagnosis,p.name patient_name,
              p.quality_status,COUNT(s.id) slide_count,
              CASE WHEN EXISTS(SELECT 1 FROM report_record r JOIN report_batch b ON b.id=r.batch_id WHERE r.business_type IN ('PATHOLOGY','PATHOLOGY_PACKAGE') AND r.business_id=c.id AND b.status='SUCCESS') THEN 'REPORTED' ELSE 'PENDING' END report_status
            FROM pathology_case c LEFT JOIN patient p ON p.id=c.patient_id LEFT JOIN slide_file s ON s.case_id=c.id AND s.deleted=0 AND s.status IN ('READY','ARCHIVED')
            GROUP BY c.id,p.name,p.quality_status ORDER BY c.id DESC
            """);}

    private List<Map<String,Object>> patients(){List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT p.* FROM patient p WHERE p.quality_status='PASSED' AND NOT EXISTS
            (SELECT 1 FROM validation_error e WHERE e.business_type='PATIENT' AND e.business_id=p.id AND e.status IN ('PENDING','FAILED')) ORDER BY p.id
            """);if(rows.isEmpty())throw new BizException("没有符合质量要求的待上报数据");return rows;}
    private Map<String,Object> one(String sql,Object...args){List<Map<String,Object>> rows=jdbc.queryForList(sql,args);return rows.isEmpty()?Map.of():rows.getFirst();}
}
