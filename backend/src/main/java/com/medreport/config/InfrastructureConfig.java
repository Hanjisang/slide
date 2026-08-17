package com.medreport.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class InfrastructureConfig {
    @Bean
    MinioClient minioClient(@Value("${app.minio.endpoint}") String endpoint,
                            @Value("${app.minio.access-key}") String accessKey,
                            @Value("${app.minio.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    @Bean
    RestClient.Builder restClientBuilder() {
        // Uvicorn's h11 server expects HTTP/1.1; the simple factory avoids h2c upgrade probes.
        return RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory());
    }
}
