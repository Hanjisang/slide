package com.medreport.file;

import com.medreport.common.BizException;
import com.medreport.slide.storage.FileMetadata;
import com.medreport.slide.storage.StorageProvider;
import com.medreport.slide.storage.StorageTarget;
import com.medreport.slide.storage.StorageTargetService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Order(20)
public class FileAssetService implements ApplicationRunner {
    public record DownloadFile(String name, long size, InputStream input) {}
    private final JdbcTemplate jdbc;
    private final StorageTargetService targets;
    private final StorageProvider storage;

    public FileAssetService(JdbcTemplate jdbc, StorageTargetService targets, StorageProvider storage) {
        this.jdbc=jdbc; this.targets=targets; this.storage=storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String,Object>> slides=jdbc.queryForList("SELECT * FROM slide_file WHERE deleted=0");
        for(Map<String,Object> slide:slides){
            long id=((Number)slide.get("id")).longValue();
            if(jdbc.queryForObject("SELECT COUNT(*) FROM file_asset WHERE file_type='SLIDE' AND business_id=?",Integer.class,id)==0){
                long targetId=slide.get("storage_target_id")==null?targets.requireClass("HOT").id():((Number)slide.get("storage_target_id")).longValue();
                registerExisting("SLIDE","PATHOLOGY",id,String.valueOf(slide.get("file_name")),
                        slide.get("display_name")==null?String.valueOf(slide.get("file_name")):String.valueOf(slide.get("display_name")),
                        targetId,String.valueOf(slide.get("object_key")),((Number)slide.get("file_size")).longValue(),String.valueOf(slide.get("md5")),"SYSTEM");
            }
        }
    }

    public List<Map<String,Object>> list(String fileType){
        String filter=fileType==null||fileType.isBlank()?"":" AND f.file_type=?";
        String sql="""
                SELECT f.*,t.name storage_target_name,t.storage_class FROM file_asset f
                JOIN storage_target t ON t.id=f.storage_target_id WHERE f.deleted=0
                """+filter+" ORDER BY f.id DESC LIMIT 1000";
        return filter.isBlank()?jdbc.queryForList(sql):jdbc.queryForList(sql,fileType.toUpperCase(Locale.ROOT));
    }

    public long upload(String fileType,String businessType,Long businessId,String displayName,MultipartFile file,String username){
        if(file.isEmpty())throw new BizException("请选择文件");
        StorageTarget target=targets.requireClass("HOT");
        String fileName=safeName(file.getOriginalFilename());
        String objectKey="files/%s/%s/v1-%s".formatted(fileType.toLowerCase(Locale.ROOT),UUID.randomUUID(),fileName);
        try{
            String md5=md5(file);
            try(InputStream input=file.getInputStream()){storage.upload(target,objectKey,input,file.getSize(),file.getContentType());}
            jdbc.update("""
                    INSERT INTO file_asset(file_type,business_type,business_id,file_name,display_name,storage_target_id,object_key,file_size,md5,current_version,created_by)
                    VALUES (?,?,?,?,?,?,?,?,?,1,?)
                    """,fileType.toUpperCase(Locale.ROOT),businessType,businessId,fileName,
                    displayName==null||displayName.isBlank()?fileName:displayName,target.id(),objectKey,file.getSize(),md5,username);
            long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            jdbc.update("INSERT INTO file_version(file_id,version_no,storage_target_id,object_key,file_size,md5,created_by) VALUES (?,1,?,?,?,?,?)",
                    id,target.id(),objectKey,file.getSize(),md5,username);
            return id;
        }catch(Exception ex){throw ex instanceof BizException biz?biz:new BizException("文件上传失败: "+ex.getMessage());}
    }

    public int uploadVersion(long fileId,MultipartFile file,String username){
        Map<String,Object> asset=find(fileId); int version=((Number)asset.get("current_version")).intValue()+1;
        StorageTarget target=targets.find(((Number)asset.get("storage_target_id")).longValue());
        String fileName=safeName(file.getOriginalFilename());
        String objectKey="files/%s/%d/v%d-%s-%s".formatted(String.valueOf(asset.get("file_type")).toLowerCase(Locale.ROOT),fileId,version,UUID.randomUUID(),fileName);
        try{
            String md5=md5(file); try(InputStream input=file.getInputStream()){storage.upload(target,objectKey,input,file.getSize(),file.getContentType());}
            jdbc.update("INSERT INTO file_version(file_id,version_no,storage_target_id,object_key,file_size,md5,created_by) VALUES (?,?,?,?,?,?,?)",
                    fileId,version,target.id(),objectKey,file.getSize(),md5,username);
            jdbc.update("UPDATE file_asset SET file_name=?,display_name=?,object_key=?,file_size=?,md5=?,current_version=? WHERE id=?",
                    fileName,fileName,objectKey,file.getSize(),md5,version,fileId);
            return version;
        }catch(Exception ex){throw ex instanceof BizException biz?biz:new BizException("版本上传失败: "+ex.getMessage());}
    }

    public List<Map<String,Object>> versions(long fileId){return jdbc.queryForList("""
            SELECT v.*,t.name storage_target_name FROM file_version v JOIN storage_target t ON t.id=v.storage_target_id
            WHERE v.file_id=? ORDER BY v.version_no DESC
            """,fileId);}

    public DownloadFile download(long id){
        Map<String,Object> asset=find(id); StorageTarget target=targets.find(((Number)asset.get("storage_target_id")).longValue());
        FileMetadata meta=storage.metadata(target,String.valueOf(asset.get("object_key")));
        return new DownloadFile(String.valueOf(asset.get("display_name")),meta.size(),storage.read(target,String.valueOf(asset.get("object_key"))));
    }

    public byte[] batchDownload(List<Long> ids){
        if(ids.isEmpty())throw new BizException("请选择文件");
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(bytes)){
            Set<String> names=new HashSet<>();
            for(long id:ids){DownloadFile file=download(id);String name=uniqueName(file.name(),names,id);zip.putNextEntry(new ZipEntry(name));
                try(InputStream input=file.input()){input.transferTo(zip);}zip.closeEntry();}
            zip.finish();return bytes.toByteArray();
        }catch(IOException ex){throw new BizException("批量下载生成失败: "+ex.getMessage());}
    }

    public void batchArchive(List<Long> ids,long targetId){
        StorageTarget target=targets.find(targetId);if(!"ARCHIVE".equals(target.storageClass()))throw new BizException("目标必须是 ARCHIVE 存储");
        for(long id:ids){Map<String,Object> asset=find(id);StorageTarget source=targets.find(((Number)asset.get("storage_target_id")).longValue());
            String sourceKey=String.valueOf(asset.get("object_key"));String targetKey="file-archive/%d/%s".formatted(id,sourceKey);
            FileMetadata copied=storage.copy(source,sourceKey,target,targetKey);
            if(!String.valueOf(asset.get("md5")).equalsIgnoreCase(copied.md5()))throw new BizException("文件 "+id+" 归档 MD5 校验失败");
            jdbc.update("UPDATE file_asset SET storage_target_id=?,object_key=? WHERE id=?",target.id(),targetKey,id);}
    }

    public void softDelete(List<Long> ids,String username){for(long id:ids)jdbc.update("UPDATE file_asset SET deleted=1,deleted_at=NOW(),deleted_by=? WHERE id=?",username,id);}

    public long registerExisting(String fileType,String businessType,long businessId,String fileName,String displayName,long targetId,
                                 String objectKey,long size,String md5,String username){
        jdbc.update("""
                INSERT INTO file_asset(file_type,business_type,business_id,file_name,display_name,storage_target_id,object_key,file_size,md5,current_version,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,1,?)
                """,fileType,businessType,businessId,fileName,displayName,targetId,objectKey,size,md5,username);
        long id=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        jdbc.update("INSERT INTO file_version(file_id,version_no,storage_target_id,object_key,file_size,md5,created_by) VALUES (?,1,?,?,?,?,?)",
        id,targetId,objectKey,size,md5,username);return id;
    }

    public long registerGenerated(Path path,String businessType,long businessId,String username){
        StorageTarget target=targets.requireClass("HOT");
        String fileName=safeName(path.getFileName().toString());
        String objectKey="files/report/%d/%s-%s".formatted(businessId,UUID.randomUUID(),fileName);
        try{
            long size=Files.size(path);String md5=md5(path);
            try(InputStream input=Files.newInputStream(path)){storage.upload(target,objectKey,input,size,"application/octet-stream");}
            return registerExisting("REPORT",businessType,businessId,fileName,fileName,target.id(),objectKey,size,md5,username);
        }catch(Exception ex){throw ex instanceof BizException biz?biz:new BizException("上报文件登记失败: "+ex.getMessage());}
    }

    public Map<String,Object> find(long id){List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM file_asset WHERE id=? AND deleted=0",id);
        if(rows.isEmpty())throw new BizException("文件不存在或已删除");return rows.getFirst();}

    private String md5(MultipartFile file)throws Exception{MessageDigest digest=MessageDigest.getInstance("MD5");
        try(DigestInputStream input=new DigestInputStream(file.getInputStream(),digest)){input.transferTo(OutputStream.nullOutputStream());}
        return HexFormat.of().formatHex(digest.digest());}
    private String md5(Path path)throws Exception{MessageDigest digest=MessageDigest.getInstance("MD5");
        try(DigestInputStream input=new DigestInputStream(Files.newInputStream(path),digest)){input.transferTo(OutputStream.nullOutputStream());}
        return HexFormat.of().formatHex(digest.digest());}
    private String safeName(String name){String value=name==null?"file":name.replace('\\','/');return value.substring(value.lastIndexOf('/')+1).replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]","_");}
    private String uniqueName(String name,Set<String> names,long id){String result=name;if(!names.add(result)){result=id+"-"+name;names.add(result);}return result;}
}
