package com.bimd.fiscalpipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FiscalPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(FiscalPipelineApplication.class, args);
    }
}
