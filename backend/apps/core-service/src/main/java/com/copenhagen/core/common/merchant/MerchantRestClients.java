package com.copenhagen.core.common.merchant;

import java.net.URI;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.copenhagen.core.common.logging.MerchantCallLogInterceptor;
import com.copenhagen.core.product.MerchantsProperties;

/**
 * merchant 호출용 {@link RestClient} 조립. Catalog(순서 24)와 Checkout(순서 27)이 같은 merchant를 부르므로
 * 타임아웃·로그 인터셉터 설정이 두 군데로 갈라지지 않도록 한 곳에 둔다.
 */
public final class MerchantRestClients {

    private MerchantRestClients() {
    }

    public static RestClient create(MerchantsProperties.MerchantConfig config) {
        return create(config, config.baseUrl(), null);
    }

    public static RestClient create(
            MerchantsProperties.MerchantConfig config, URI endpoint, String platformProfileUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.connectTimeout());
        factory.setReadTimeout(config.requestTimeout());
        return RestClient.builder()
                .baseUrl(endpoint.toString())
                .requestFactory(factory)
                .requestInterceptor(new MerchantCallLogInterceptor(config.id(), platformProfileUrl))
                .build();
    }
}
