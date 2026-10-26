package com.copenhagen.pgmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PgMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(PgMockApplication.class, args);
    }
}
