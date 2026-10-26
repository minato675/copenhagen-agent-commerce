package com.copenhagen.merchantmock.profile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.copenhagen.merchantmock.security.SigningKeyProvider;
import com.copenhagen.merchantmock.support.MerchantProperties;
import org.springframework.stereotype.Service;

/**
 * Business Profile 응답을 설정에서 조립한다.
 *
 * <p>정적 JSON 파일이 아니라 코드로 만든다. endpoint 와 kid 와 공개키가 환경마다 달라서다.
 *
 * <p>Cart Capability 는 광고하지 않는다. Core 가 자기 장바구니를 소유하기로 2026-09-08 에
 * 합의했다(DESIGN.md 1절).
 */
@Service
public class BusinessProfileService {

    private static final String SHOPPING_SERVICE = "dev.ucp.shopping";
    private static final String REST_TRANSPORT = "rest";
    private static final List<String> SHOPPING_CAPABILITIES = List.of(
            "dev.ucp.shopping.catalog.search",
            "dev.ucp.shopping.catalog.lookup",
            "dev.ucp.shopping.checkout",
            "dev.ucp.shopping.order",
            "dev.ucp.shopping.fulfillment");

    private final MerchantProperties properties;
    private final SigningKeyProvider signingKeyProvider;

    public BusinessProfileService(MerchantProperties properties, SigningKeyProvider signingKeyProvider) {
        this.properties = properties;
        this.signingKeyProvider = signingKeyProvider;
    }

    /** 몰 하나의 Profile. 모르는 몰은 404(393). endpoint 만 몰마다 다르고 키·Capability 는 같다. */
    public BusinessProfileResponse build(String merchantId) {
        properties.requireMall(merchantId);
        String version = properties.ucpVersion();
        BusinessProfileResponse.Ucp ucp = new BusinessProfileResponse.Ucp(
                version,
                services(version, merchantId),
                capabilities(version),
                paymentHandlers());
        return new BusinessProfileResponse(ucp, keys());
    }

    private Map<String, List<BusinessProfileResponse.Service>> services(String version, String merchantId) {
        BusinessProfileResponse.Service rest = new BusinessProfileResponse.Service(
                version, REST_TRANSPORT, properties.restEndpoint(merchantId));
        Map<String, List<BusinessProfileResponse.Service>> services = new LinkedHashMap<>();
        services.put(SHOPPING_SERVICE, List.of(rest));
        return services;
    }

    private Map<String, List<BusinessProfileResponse.Capability>> capabilities(String version) {
        List<BusinessProfileResponse.Capability> declared = List.of(new BusinessProfileResponse.Capability(version));
        Map<String, List<BusinessProfileResponse.Capability>> capabilities = new LinkedHashMap<>();
        for (String name : SHOPPING_CAPABILITIES) {
            capabilities.put(name, declared);
        }
        MerchantProperties.Capabilities configured = properties.capabilities();
        if (configured != null && configured.ap2Like() != null && configured.ap2Like().enabled()) {
            capabilities.put(configured.ap2Like().namespace(), declared);
        }
        return capabilities;
    }

    private Map<String, List<BusinessProfileResponse.PaymentHandler>> paymentHandlers() {
        MerchantProperties.PaymentHandler handler = properties.paymentHandler();
        Map<String, List<BusinessProfileResponse.PaymentHandler>> handlers = new LinkedHashMap<>();
        if (handler == null) {
            return handlers;
        }
        handlers.put(handler.namespace(),
                List.of(new BusinessProfileResponse.PaymentHandler(handler.id(), handler.version())));
        return handlers;
    }

    private List<Map<String, Object>> keys() {
        return signingKeyProvider.publicJwk().map(List::of).orElseGet(List::of);
    }
}
