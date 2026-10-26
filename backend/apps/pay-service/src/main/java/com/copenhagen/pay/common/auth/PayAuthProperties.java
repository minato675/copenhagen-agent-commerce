package com.copenhagen.pay.common.auth;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "pay.auth")
public record PayAuthProperties(
        Map<String, String> userTokens,
        Map<String, String> serviceTokens,
        Map<String, String> merchantTokens,
        String userTokensJson,
        String coreServiceTokensJson,
        String merchantServiceTokensJson) {

    public PayAuthProperties(
            Map<String, String> userTokens,
            Map<String, String> serviceTokens) {
        this(userTokens, serviceTokens, Map.of(), "", "", "");
    }

    public PayAuthProperties(
            Map<String, String> userTokens,
            Map<String, String> serviceTokens,
            Map<String, String> merchantTokens) {
        this(userTokens, serviceTokens, merchantTokens, "", "", "");
    }

    @ConstructorBinding
    public PayAuthProperties {
        userTokens = userTokens == null ? Map.of() : Map.copyOf(userTokens);
        serviceTokens = serviceTokens == null ? Map.of() : Map.copyOf(serviceTokens);
        merchantTokens = merchantTokens == null ? Map.of() : Map.copyOf(merchantTokens);
        userTokensJson = normalize(userTokensJson);
        coreServiceTokensJson = normalize(coreServiceTokensJson);
        merchantServiceTokensJson = normalize(merchantServiceTokensJson);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
