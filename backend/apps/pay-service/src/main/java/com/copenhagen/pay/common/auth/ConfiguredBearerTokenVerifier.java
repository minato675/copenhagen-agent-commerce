package com.copenhagen.pay.common.auth;

import com.nimbusds.jose.util.JSONObjectUtils;
import java.text.ParseException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredBearerTokenVerifier implements PayTokenVerifier {

    private final JwtBearerTokenVerifier jwtTokenVerifier;
    private final Map<String, PayPrincipal> configuredPrincipals;

    public ConfiguredBearerTokenVerifier(PayAuthProperties properties) {
        this(
                properties,
                new JwtBearerTokenVerifier(
                        new PayJwtProperties(false, "", "", "", 0),
                        Clock.systemUTC()));
    }

    @Autowired
    public ConfiguredBearerTokenVerifier(
            PayAuthProperties properties,
            JwtBearerTokenVerifier jwtTokenVerifier) {
        this.jwtTokenVerifier = jwtTokenVerifier;
        this.configuredPrincipals = loadConfiguredPrincipals(properties);
    }

    @Override
    public Optional<PayPrincipal> verify(String bearerToken) {
        PayPrincipal configuredPrincipal = configuredPrincipals.get(bearerToken);
        if (configuredPrincipal != null && configuredPrincipal.type() == PayPrincipal.Type.SERVICE) {
            return Optional.of(configuredPrincipal);
        }

        if (jwtTokenVerifier.enabled()) {
            return jwtTokenVerifier.verify(bearerToken);
        }

        if (configuredPrincipal != null && configuredPrincipal.type() == PayPrincipal.Type.USER) {
            return Optional.of(configuredPrincipal);
        }

        return Optional.empty();
    }

    private Map<String, PayPrincipal> loadConfiguredPrincipals(PayAuthProperties properties) {
        Map<String, PayPrincipal> principals = new HashMap<>();
        addLegacyMappings(principals, properties.userTokens(), PayPrincipal.Type.USER);
        addLegacyServiceMappings(principals, properties.serviceTokens());
        addLegacyMerchantMappings(principals, properties.merchantTokens());
        addJsonMappings(principals, properties.userTokensJson(), PayPrincipal.Type.USER);
        addJsonMappings(
                principals,
                properties.coreServiceTokensJson(),
                PayPrincipal.ServiceType.CORE_SERVICE);
        addJsonMappings(
                principals,
                properties.merchantServiceTokensJson(),
                PayPrincipal.ServiceType.MERCHANT_SERVICE);
        return Map.copyOf(principals);
    }

    private void addLegacyMappings(
            Map<String, PayPrincipal> principals,
            Map<String, String> mappings,
            PayPrincipal.Type type) {
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String token = requireValue(entry.getKey());
            String subject = requireValue(entry.getValue());
            PayPrincipal principal = new PayPrincipal(type, subject);
            addMapping(principals, token, principal);
        }
    }

    private void addLegacyServiceMappings(
            Map<String, PayPrincipal> principals,
            Map<String, String> mappings) {
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String token = requireValue(entry.getKey());
            String subject = requireValue(entry.getValue());
            PayPrincipal.ServiceType serviceType = "core-service".equals(subject)
                    ? PayPrincipal.ServiceType.CORE_SERVICE
                    : PayPrincipal.ServiceType.UNKNOWN;
            addMapping(
                    principals,
                    token,
                    new PayPrincipal(PayPrincipal.Type.SERVICE, subject, serviceType));
        }
    }

    private void addLegacyMerchantMappings(
            Map<String, PayPrincipal> principals,
            Map<String, String> mappings) {
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String token = requireValue(entry.getKey());
            String merchantId = requireValue(entry.getValue());
            addMapping(
                    principals,
                    token,
                    new PayPrincipal(
                            PayPrincipal.Type.SERVICE,
                            merchantId,
                            PayPrincipal.ServiceType.MERCHANT_SERVICE));
        }
    }

    private void addJsonMappings(
            Map<String, PayPrincipal> principals,
            String json,
            PayPrincipal.Type type) {
        if (json.isBlank()) {
            return;
        }

        Map<String, Object> mappings = parseJsonMappings(json);
        for (Map.Entry<String, Object> entry : mappings.entrySet()) {
            String subject = requireValue(entry.getKey());
            if (!(entry.getValue() instanceof String token)) {
                throw invalidConfiguration();
            }
            addMapping(principals, requireValue(token), new PayPrincipal(type, subject));
        }
    }

    private void addJsonMappings(
            Map<String, PayPrincipal> principals,
            String json,
            PayPrincipal.ServiceType serviceType) {
        if (json.isBlank()) {
            return;
        }

        Map<String, Object> mappings = parseJsonMappings(json);
        for (Map.Entry<String, Object> entry : mappings.entrySet()) {
            String subject = requireValue(entry.getKey());
            if (!(entry.getValue() instanceof String token)) {
                throw invalidConfiguration();
            }
            addMapping(
                    principals,
                    requireValue(token),
                    new PayPrincipal(PayPrincipal.Type.SERVICE, subject, serviceType));
        }
    }

    private Map<String, Object> parseJsonMappings(String json) {
        try {
            return JSONObjectUtils.parse(json);
        } catch (ParseException | RuntimeException exception) {
            throw new IllegalStateException(
                    "Configured authentication token mappings are invalid",
                    exception);
        }
    }

    private void addMapping(
            Map<String, PayPrincipal> principals,
            String token,
            PayPrincipal principal) {
        if (principals.putIfAbsent(token, principal) != null) {
            throw new IllegalStateException(
                    "Configured authentication token mappings contain a duplicate token");
        }
    }

    private String requireValue(String value) {
        if (value == null || value.isBlank()) {
            throw invalidConfiguration();
        }
        return value.trim();
    }

    private IllegalStateException invalidConfiguration() {
        return new IllegalStateException(
                "Configured authentication token mappings contain a blank or invalid value");
    }
}
