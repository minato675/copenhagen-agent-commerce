package com.copenhagen.merchantmock.profile;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /.well-known/ucp 응답. Core 가 Discovery 단계에서 읽는다.
 *
 * <p>필드 이름은 UCP 2026-08-25 REST Binding 을 따른다. payment_handlers 만 snake_case 다.
 * 값이 없으면 키를 생략한다는 규칙(DESIGN.md 17절)은 여기 적용하지 않는다.
 * Profile 은 서명 대상이 아니라 다이제스트가 걸리지 않는다.
 */
public record BusinessProfileResponse(Ucp ucp, List<Map<String, Object>> keys) {

    public record Ucp(
            String version,
            Map<String, List<Service>> services,
            Map<String, List<Capability>> capabilities,
            @JsonProperty("payment_handlers") Map<String, List<PaymentHandler>> paymentHandlers) {
    }

    public record Service(String version, String transport, String endpoint) {
    }

    public record Capability(String version) {
    }

    public record PaymentHandler(String id, String version) {
    }
}
