package com.medreport.system;

import com.medreport.slide.storage.S3StorageProvider;
import com.medreport.slide.storage.StorageTargetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.util.*;

@Service
public class SystemMetricsService {
    private final JdbcTemplate jdbc;private final StorageTargetService targets;private final S3StorageProvider storage;
    public SystemMetricsService(JdbcTemplate jdbc,StorageTargetService targets,S3StorageProvider storage){this.jdbc=jdbc;this.targets=targets;this.storage=storage;}

    public Map<String,Object> metrics(){
        Map<String,Object> result=new LinkedHashMap<>();com.sun.management.OperatingSystemMXBean os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        double cpu=os.getCpuLoad();result.put("cpu",Map.of("usagePercent",cpu<0?"UNKNOWN":round(cpu*100),"processors",os.getAvailableProcessors()));
        long totalMemory=os.getTotalMemorySize(),freeMemory=os.getFreeMemorySize();result.put("memory",Map.of("totalBytes",totalMemory,"usedBytes",totalMemory-freeMemory,"usagePercent",percent(totalMemory-freeMemory,totalMemory)));
        MemoryUsage heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();result.put("jvmHeap",Map.of("usedBytes",heap.getUsed(),"committedBytes",heap.getCommitted(),"maxBytes",heap.getMax(),"usagePercent",percent(heap.getUsed(),heap.getMax())));
        long total=0,usable=0;for(FileStore store:FileSystems.getDefault().getFileStores())try{total+=store.getTotalSpace();usable+=store.getUsableSpace();}catch(Exception ignored){}
        result.put("disk",Map.of("totalBytes",total,"usedBytes",Math.max(0,total-usable),"availableBytes",usable,"usagePercent",percent(total-usable,total)));
        result.put("storageTargets",storageTargets());result.put("tasks",tasks());return result;
    }

    public List<Map<String,Object>> storageTargets(){List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:targets.listSafe()){Map<String,Object> item=new LinkedHashMap<>(row);try{item.putAll(storage.usage(targets.find(((Number)row.get("id")).longValue())));item.put("status","UP");}
            catch(Exception ex){item.put("status","DOWN");item.put("message",ex.getMessage());item.put("capacityBytes","UNKNOWN");item.put("availableBytes","UNKNOWN");}result.add(item);}return result;}

    private Map<String,Object> tasks(){Map<String,Object> tasks=new LinkedHashMap<>();
        tasks.put("collect",statuses("collect_log"));tasks.put("slide",Map.of("WAITING",count("SELECT COUNT(*) FROM slide_file WHERE status IN ('UPLOADING','UPLOADED')"),"RUNNING",count("SELECT COUNT(*) FROM slide_file WHERE status='PARSING'"),"SUCCESS",count("SELECT COUNT(*) FROM slide_file WHERE status IN ('READY','ARCHIVED')"),"FAILED",count("SELECT COUNT(*) FROM slide_file WHERE status='FAILED'")));
        tasks.put("archive",statuses("archive_task"));tasks.put("backup",statuses("backup_task"));tasks.put("report",statuses("report_batch"));return tasks;}
    private Map<String,Long> statuses(String table){Map<String,Long> result=new LinkedHashMap<>();for(String status:List.of("PENDING","COPYING","VERIFYING","REPORTING","SUCCESS","FAILED")){long value=count("SELECT COUNT(*) FROM "+table+" WHERE status='"+status+"'");if(value>0)result.put(status,value);}return result;}
    private long count(String sql){Number value=jdbc.queryForObject(sql,Number.class);return value==null?0:value.longValue();}
    private double percent(long used,long total){return total<=0?0:round(used*100d/total);}private double round(double value){return Math.round(value*100d)/100d;}
}
