package com.medreport.backup;

import com.medreport.common.BizException;
import com.medreport.file.FileAssetService;
import com.medreport.slide.storage.FileMetadata;
import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTarget;
import com.medreport.slide.storage.StorageTargetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class BackupService {
    private final JdbcTemplate jdbc;private final FileAssetService files;private final StorageTargetService targets;private final StorageProvider storage;
    public BackupService(JdbcTemplate jdbc,FileAssetService files,StorageTargetService targets,StorageProvider storage){this.jdbc=jdbc;this.files=files;this.targets=targets;this.storage=storage;}

    public Map<String,Object> backup(long fileId,Long targetId){
        Map<String,Object> file=files.find(fileId);StorageTarget source=targets.find(((Number)file.get("storage_target_id")).longValue());
        StorageTarget target=targetId==null?targets.requireClass("BACKUP"):targets.find(targetId);
        if(!"BACKUP".equals(target.storageClass()))throw new BizException("目标必须是 BACKUP 存储");
        jdbc.update("INSERT INTO backup_task(file_id,source_storage_id,target_storage_id,status,progress,source_md5) VALUES (?,?,?,'PENDING',0,?)",
                fileId,source.id(),target.id(),file.get("md5"));long taskId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        String targetKey="backup/%s/file-%d/v%s/%s".formatted(LocalDate.now(),fileId,file.get("current_version"),file.get("file_name"));
        try{
            jdbc.update("UPDATE backup_task SET status='COPYING',progress=10,started_at=NOW(),target_object_key=? WHERE id=?",targetKey,taskId);
            FileMetadata copied=storage.copy(source,String.valueOf(file.get("object_key")),target,targetKey);
            if(!Objects.equals(String.valueOf(file.get("md5")).toLowerCase(),copied.md5().toLowerCase()))throw new BizException("备份 MD5 校验失败");
            jdbc.update("UPDATE backup_task SET status='SUCCESS',progress=100,target_md5=?,finished_at=NOW() WHERE id=?",copied.md5(),taskId);
            return jdbc.queryForMap("SELECT * FROM backup_task WHERE id=?",taskId);
        }catch(Exception ex){jdbc.update("UPDATE backup_task SET status='FAILED',error_message=?,finished_at=NOW() WHERE id=?",ex.getMessage(),taskId);
            throw ex instanceof BizException biz?biz:new BizException("备份失败: "+ex.getMessage());}
    }

    @Scheduled(fixedDelay=3600000,initialDelay=120000)
    public void scheduledBackups(){
        for(Map<String,Object> policy:jdbc.queryForList("SELECT * FROM backup_policy WHERE enabled=1")){
            String frequency=String.valueOf(policy.get("frequency"));
            if("WEEKLY".equals(frequency)&&LocalDate.now().getDayOfWeek()!= DayOfWeek.MONDAY)continue;
            if(!List.of("DAILY","WEEKLY").contains(frequency))continue;
            long targetId=((Number)policy.get("target_storage_id")).longValue();
            for(Map<String,Object> asset:jdbc.queryForList("SELECT id FROM file_asset WHERE deleted=0")){
                long fileId=((Number)asset.get("id")).longValue();
                int count=jdbc.queryForObject("SELECT COUNT(*) FROM backup_task WHERE file_id=? AND target_storage_id=? AND status='SUCCESS' AND finished_at>=CURRENT_DATE",Integer.class,fileId,targetId);
                if(count==0)try{backup(fileId,targetId);}catch(Exception ignored){}
            }
        }
    }
}
