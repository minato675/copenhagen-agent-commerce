package com.copenhagen.pay.payment.credential.mandate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "pay.mandate")
public record MandateProperties(
        String issuer,
        String keyId,
        String signingPrivateKey,
        String encryptionKey,
        String publicKeyJwk,
        String previousVerificationKeysJson,
        long profileCacheSeconds,
        boolean requireConfiguredKey) {

    @ConstructorBinding
    public MandateProperties {
    }

    public MandateProperties(
            String issuer,
            String keyId,
            String signingPrivateKey,
            String encryptionKey) {
        this(issuer, keyId, signingPrivateKey, encryptionKey, "", "[]", 300, false);
    }
}
