package com.medreport.file;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import com.medreport.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequirePermission("FILE_MANAGE")
public class FileController {
    private final FileAssetService files;private final AuditService audit;
    public FileController(FileAssetService files,AuditService audit){this.files=files;this.audit=audit;}

    @GetMapping public ApiResponse<List<Map<String,Object>>> list(@RequestParam(required=false)String fileType){return ApiResponse.ok(files.list(fileType));}
    @PostMapping(value="/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String,Object>> upload(@RequestParam String fileType,@RequestParam(required=false)String businessType,
      @RequestParam(required=false)Long businessId,@RequestParam(required=false)String displayName,@RequestPart MultipartFile file,HttpServletRequest request){
        long id=files.upload(fileType,businessType,businessId,displayName,file,AuditService.username(request));
        audit.log(request,"上传文件","文件管理",id,"SUCCESS",file.getOriginalFilename());return ApiResponse.ok(Map.of("id",id));}
    @PostMapping(value="/{id}/versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String,Object>> version(@PathVariable long id,@RequestPart MultipartFile file,HttpServletRequest request){
        int version=files.uploadVersion(id,file,AuditService.username(request));audit.log(request,"上传文件版本","文件管理",id,"SUCCESS","V"+version);
        return ApiResponse.ok(Map.of("version",version));}
    @GetMapping("/{id}/versions") public ApiResponse<List<Map<String,Object>>> versions(@PathVariable long id){return ApiResponse.ok(files.versions(id));}
    @GetMapping("/{id}/download") public ResponseEntity<InputStreamResource> download(@PathVariable long id){FileAssetService.DownloadFile file=files.download(id);
        return ResponseEntity.ok().contentLength(file.size()).contentType(MediaType.APPLICATION_OCTET_STREAM)
          .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString()).body(new InputStreamResource(file.input()));}
    @PostMapping("/batch/download") public ResponseEntity<byte[]> batchDownload(@RequestBody Map<String,List<Long>> body){byte[] zip=files.batchDownload(body.getOrDefault("ids",List.of()));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=files.zip").body(zip);}
    @PostMapping("/batch/archive") public ApiResponse<Void> batchArchive(@RequestBody Map<String,Object> body,HttpServletRequest request){
        files.batchArchive(ids(body),((Number)body.get("targetStorageId")).longValue());audit.log(request,"批量归档文件","文件管理",null,"SUCCESS",String.valueOf(ids(body).size()));return ApiResponse.ok();}
    @DeleteMapping("/batch") public ApiResponse<Void> delete(@RequestBody Map<String,Object> body,HttpServletRequest request){
        files.softDelete(ids(body),AuditService.username(request));audit.log(request,"批量删除文件","文件管理",null,"SUCCESS",String.valueOf(ids(body).size()));return ApiResponse.ok();}
    private List<Long> ids(Map<String,Object> body){Object value=body.get("ids");if(!(value instanceof List<?> list))return List.of();return list.stream().filter(Number.class::isInstance).map(Number.class::cast).map(Number::longValue).toList();}
}
