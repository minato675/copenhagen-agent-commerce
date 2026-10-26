package com.copenhagen.core.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.copenhagen.core.common.logging.MerchantCallLogInterceptor;

/** Pay 내부 API 호출용 {@link RestClient}·{@link PaymentApprovalClient} 조립(이슈 410). */
@Configuration
@EnableConfigurationProperties(PayServiceProperties.class)
public class PaymentApprovalConfiguration {

    private static final String TARGET = "pay-service";

    @Bean
    public PaymentApprovalClient paymentApprovalClient(PayServiceProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.requestTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.serviceToken())
                .requestInterceptor(new MerchantCallLogInterceptor(TARGET))
                .build();
        return new PaymentApprovalClient(restClient);
    }
}
