package com.copenhagen.pay.payment.merchant;

import java.util.List;
import java.util.Map;

public record MerchantProfileResponse(
        Map<String, Object> ucp,
        List<Map<String, Object>> keys) {

    public MerchantProfileResponse {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
}
