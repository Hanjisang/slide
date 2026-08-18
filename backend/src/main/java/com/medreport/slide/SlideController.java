package com.medreport.slide;

import com.medreport.auth.AuthInterceptor;
import com.medreport.auth.RequirePermission;
import com.medreport.auth.TokenService;
import com.medreport.common.ApiResponse;
import com.medreport.slide.archive.ArchiveService;
import com.medreport.slide.file.SlideFileService;
import com.medreport.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/slides")
public class SlideController {
    private final SlideService slides;
    private final SlideFileService files;
    private final ArchiveService archives;
    private final AuditService audit;

    public SlideController(SlideService slides, SlideFileService files, ArchiveService archives, AuditService audit) {
        this.slides=slides; this.files=files; this.archives=archives; this.audit=audit;
    }

    @GetMapping
    @RequirePermission("SLIDE_VIEW")
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam Map<String, String> filters) { return ApiResponse.ok(files.list(filters)); }

    @GetMapping("/cases")
    @RequirePermission("SLIDE_VIEW")
    public ApiResponse<List<Map<String, Object>>> cases() { return ApiResponse.ok(files.cases()); }

    @PostMapping(value="/upload", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission("SLIDE_UPLOAD")
    public ApiResponse<Map<String, Object>> upload(@RequestParam long caseId, @RequestParam String slideNo, @RequestPart MultipartFile file) {
        long id=slides.upload(caseId,slideNo,file); return ApiResponse.ok(Map.of("id",id,"slide",files.find(id)));
    }

    @PostMapping("/{id}/analyze")
    @RequirePermission("SLIDE_UPLOAD")
    public ApiResponse<Map<String,Object>> analyze(@PathVariable long id, HttpServletRequest request) {
        Map<String,Object> result=slides.analyze(id); audit.log(request,"重新解析切片","数字切片",id,"SUCCESS",String.valueOf(result.get("status")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/{id}/archive")
    @RequirePermission("SLIDE_ARCHIVE")
    public ApiResponse<Map<String,Object>> archive(@PathVariable long id,@RequestBody(required=false) Map<String,Object> body,HttpServletRequest request) {
        Map<String,Object> data=body==null?Map.of():body;
        Long target=data.get("targetStorageId") instanceof Number n?n.longValue():null;
        return ApiResponse.ok(archives.archive(id,target,data.get("note")==null?null:String.valueOf(data.get("note")),request));
    }

    @PutMapping("/{id}/rename")
    @RequirePermission("SLIDE_RENAME")
    public ApiResponse<Void> rename(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        files.rename(id,String.valueOf(body.getOrDefault("displayName","")));
        audit.log(request,"重命名切片","数字切片",id,"SUCCESS",String.valueOf(body.get("displayName"))); return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("SLIDE_DELETE")
    public ApiResponse<Void> delete(@PathVariable long id,HttpServletRequest request) {
        TokenService.Session session=(TokenService.Session)request.getAttribute(AuthInterceptor.SESSION_ATTRIBUTE);
        files.softDelete(id,session.username()); audit.log(request,"逻辑删除切片","数字切片",id,"SUCCESS",null); return ApiResponse.ok();
    }

    @GetMapping("/{id}/download")
    @RequirePermission("SLIDE_DOWNLOAD")
    public ResponseEntity<InputStreamResource> download(@PathVariable long id,HttpServletRequest request) {
        SlideFileService.DownloadFile file=files.download(id); audit.log(request,"下载切片","数字切片",id,"SUCCESS",file.fileName());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(file.input()));
    }

    @GetMapping("/{id}") @RequirePermission("SLIDE_VIEW") public ApiResponse<Map<String,Object>> detail(@PathVariable long id){return ApiResponse.ok(files.find(id));}
    @GetMapping("/{id}/tiles/{level}/{x}/{y}")
    @RequirePermission("SLIDE_VIEW")
    public ResponseEntity<byte[]> tile(@PathVariable long id,@PathVariable int level,@PathVariable int x,@PathVariable int y){
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.maxAge(Duration.ofDays(1))).body(slides.tile(id,level,x,y));
    }
    @GetMapping("/{id}/thumbnail")
    @RequirePermission("SLIDE_VIEW")
    public ResponseEntity<byte[]> thumbnail(@PathVariable long id){return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1))).body(slides.thumbnail(id));}
}
