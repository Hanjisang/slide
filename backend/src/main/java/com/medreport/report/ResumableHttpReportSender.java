package com.medreport.report;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import com.medreport.system.AlertService;

import static com.medreport.report.ReportModels.*;

/** Chunked HTTP sender. It reads one chunk at a time and reconciles receiver status before resuming. */
@Component
public class ResumableHttpReportSender implements ReportSender {
    private final RestClient client; private final AlertService alerts;
    public ResumableHttpReportSender(RestClient.Builder b,AlertService alerts){client=b.build();this.alerts=alerts;}
    @Override public boolean supports(String senderType){return "HTTP_RESUMABLE".equalsIgnoreCase(senderType);}
    @Override public SendResult send(ReportPackage p){
        try { Path file=p.path(); long total=Files.size(file); int chunk=8*1024*1024;
            Map<?,?> init=client.post().uri(p.endpoint()+"/init").contentType(MediaType.APPLICATION_JSON).body(Map.of("totalBytes",total,"chunkSize",chunk)).retrieve().body(Map.class);
            Map<?,?> data=(Map<?,?>)init.get("data"); String upload=String.valueOf(data.get("uploadId")); int n=((Number)data.get("totalChunks")).intValue();
            Map<?,?> st=client.get().uri(p.endpoint()+"/"+upload+"/status").retrieve().body(Map.class); Set<Integer> done=new HashSet<>(); Object sd=((Map<?,?>)st.get("data")).get("uploadedChunks"); if(sd instanceof List<?> l)for(Object x:l)done.add(((Number)x).intValue());
            try(RandomAccessFile in=new RandomAccessFile(file.toFile(),"r")){byte[] buf=new byte[chunk]; for(int i=0;i<n;i++){if(done.contains(i))continue;in.seek((long)i*chunk);int len=in.read(buf);byte[] payload=Arrays.copyOf(buf,len);String sha=sha(payload);client.put().uri(p.endpoint()+"/"+upload+"/chunks/"+i).contentType(MediaType.APPLICATION_OCTET_STREAM).header("X-Chunk-SHA256",sha).header("Content-Range","bytes "+((long)i*chunk)+"-"+((long)i*chunk+len-1)+"/"+total).body(new ByteArrayResource(payload)).retrieve().toBodilessEntity();}}
            Map<?,?> fin=client.post().uri(p.endpoint()+"/"+upload+"/complete").retrieve().body(Map.class);String remote=String.valueOf(((Map<?,?>)fin.get("data")).get("remoteSha256"));String local=sha(file);return new SendResult(local.equalsIgnoreCase(remote),"remoteSha256="+remote);
        }catch(Exception e){alerts.emit("REPORT_TRANSFER_FAILED","WARNING","REPORT",p.batchId(),"断点续传失败: "+e.getMessage());return new SendResult(false,"resumable transfer failed: "+e.getMessage());}
    }
    private String sha(byte[] b)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(b));}
    private String sha(Path p)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[]b=new byte[1024*1024];for(int n;(n=in.read(b))>0;)d.update(b,0,n);}return hex(d.digest());}
    private String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
}
