package com.copenhagen.pay.payment.merchant;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services.merchant")
public record MerchantServiceProperties(
        String baseUrl,
        String profilePath,
        long connectTimeoutMillis,
        long requestTimeoutMillis,
        String allowedAlgorithms) {

    private static final String DEFAULT_PROFILE_PATH = "/.well-known/ucp";

    public MerchantServiceProperties {
        baseUrl = normalize(baseUrl);
        profilePath = normalize(profilePath);
        allowedAlgorithms = normalize(allowedAlgorithms);
        if (baseUrl.isBlank()) {
            throw new IllegalArgumentException("services.merchant.base-url must not be blank");
        }
        if (profilePath.isBlank()) {
            profilePath = DEFAULT_PROFILE_PATH;
        } else if (!profilePath.startsWith("/")) {
            profilePath = "/" + profilePath;
        }
        if (connectTimeoutMillis <= 0 || requestTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Merchant profile timeout values must be positive");
        }
        if (parseAlgorithmNames(allowedAlgorithms).isEmpty()) {
            throw new IllegalArgumentException("services.merchant.allowed-algorithms must not be blank");
        }
    }

    public List<String> allowedAlgorithmNames() {
        return parseAlgorithmNames(allowedAlgorithms);
    }

    private List<String> parseAlgorithmNames(String algorithms) {
        return Arrays.stream(algorithms.split(","))
                .map(String::trim)
                .filter(algorithm -> !algorithm.isBlank())
                .map(algorithm -> algorithm.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
