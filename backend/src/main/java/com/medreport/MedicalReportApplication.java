package com.medreport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MedicalReportApplication {
    public static void main(String[] args) {
        SpringApplication.run(MedicalReportApplication.class, args);
    }
}

