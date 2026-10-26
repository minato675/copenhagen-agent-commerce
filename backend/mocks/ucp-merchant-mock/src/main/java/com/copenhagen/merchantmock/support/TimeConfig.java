package com.copenhagen.merchantmock.support;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각은 전부 Clock 에서 읽는다. 테스트가 고정 시각을 넣기 위해서다.
 *
 * <p>응답의 시각은 RFC 3339 offset 포함 초 단위다(DESIGN.md 17절). 시연이 한국이라 Asia/Seoul 로 찍는다.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
