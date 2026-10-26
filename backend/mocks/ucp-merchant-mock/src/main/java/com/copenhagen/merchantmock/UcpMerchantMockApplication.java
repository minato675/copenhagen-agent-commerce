package com.copenhagen.merchantmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} 은 여기 있다. 워커마다 켜고 끄는 설정이 따로 있어(DESIGN.md 3.4절)
 * 스케줄링 자체를 워커 한 개에 달아 두면 그 워커를 끄는 순간 다른 워커까지 조용히 멈췄다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class UcpMerchantMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(UcpMerchantMockApplication.class, args);
    }
}
