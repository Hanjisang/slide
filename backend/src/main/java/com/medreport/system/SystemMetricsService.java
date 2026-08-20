package com.medreport.system;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SystemMetricsService {
    private final MonitoringSnapshotService snapshots;
    public SystemMetricsService(MonitoringSnapshotService snapshots){this.snapshots=snapshots;}

    @SuppressWarnings("unchecked")
    public Map<String,Object> metrics(){
        Map<String,Object> snapshot=snapshots.snapshot();Map<String,Object> result=new LinkedHashMap<>((Map<String,Object>)snapshot.get("resources"));
        result.put("storageTargets",snapshot.get("storageTargets"));result.put("tasks",snapshot.get("tasks"));return result;
    }
}
