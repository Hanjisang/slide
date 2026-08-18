package com.medreport.slide.worker;

import com.medreport.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class SlideWorkerClient {
    private final RestClient worker;

    public SlideWorkerClient(RestClient.Builder builder, @Value("${app.slide-worker-url}") String workerUrl) {
        this.worker = builder.baseUrl(workerUrl).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(long id, String bucket, String objectKey, String fileName) {
        Map<String, Object> result = worker.post().uri("/api/slides/{id}/analyze", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("bucket", bucket, "objectKey", objectKey, "fileName", fileName)).retrieve().body(Map.class);
        if (result == null) throw new BizException("Slide Worker 未返回解析结果");
        return result;
    }

    public byte[] tile(long id, int level, int x, int y) {
        try {
            return worker.get().uri(uri -> uri.path("/api/slides/{id}/tiles/{level}/{x}/{y}")
                    .queryParam("tile_size", 256).build(id, level, x, y)).retrieve().body(byte[].class);
        } catch (Exception ex) {
            throw new BizException("Tile 读取失败: " + ex.getMessage());
        }
    }

    public byte[] thumbnail(long id) {
        try { return worker.get().uri("/api/slides/{id}/thumbnail", id).retrieve().body(byte[].class); }
        catch (Exception ex) { throw new BizException("缩略图读取失败: " + ex.getMessage()); }
    }
}
