package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionItem;
import com.copenhagen.pay.payment.authorization.CheckoutHashCodec;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MandateSigningService {

    private static final String AUDIENCE_PREFIX = "merchant:";
    private static final String KEY_ALGORITHM = "EC";

    private final MandateProperties properties;
    private final ECPrivateKey signingKey;
    private final String signingKeyId;

    @Autowired
    public MandateSigningService(
            MandateProperties properties,
            MandateKeySet keySet) {
        this.properties = properties;
        this.signingKey = keySet.signingKey();
        this.signingKeyId = keySet.signingKeyId();
    }

    MandateSigningService(MandateProperties properties) {
        this.properties = properties;
        this.signingKey = resolveLegacyTestSigningKey(properties.signingPrivateKey());
        this.signingKeyId = properties.keyId();
    }

    public String createCheckoutMandate(
            AuthorizationSession session,
            AuthorizationSessionItem item,
            String mandateId,
            Instant issuedAt) {
        JWTClaimsSet claims = baseClaims(session, item, mandateId, issuedAt)
                .claim("checkoutMandateType", "CHECKOUT")
                .build();
        return sign(claims);
    }

    public String createPaymentMandate(
            AuthorizationSession session,
            AuthorizationSessionItem item,
            String mandateId,
            String transactionId,
            String paymentMethodRef,
            Instant issuedAt) {
        JWTClaimsSet claims = baseClaims(session, item, mandateId, issuedAt)
                .claim("paymentMandateType", "PAYMENT")
                .claim("transactionId", transactionId)
                .claim("paymentMethodRef", paymentMethodRef)
                .build();
        return sign(claims);
    }

    private JWTClaimsSet.Builder baseClaims(
            AuthorizationSession session,
            AuthorizationSessionItem item,
            String mandateId,
            Instant issuedAt) {
        return new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(AUDIENCE_PREFIX + item.merchantId())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(session.expiresAt()))
                .jwtID(mandateId)
                .claim("merchantId", item.merchantId())
                .claim("checkoutId", item.externalCheckoutId())
                .claim("checkoutHash", CheckoutHashCodec.toExternalHash(item.checkoutDigest()))
                .claim("purchaseRequestId", session.purchaseRequestId())
                .claim("amountMinor", item.amountMinor())
                .claim("currency", item.currency());
    }

    private String sign(JWTClaimsSet claims) {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(signingKeyId)
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT signedJwt = new SignedJWT(header, claims);
        try {
            JWSSigner signer = new ECDSASigner(signingKey);
            signedJwt.sign(signer);
            return signedJwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Mandate signing failed", exception);
        }
    }

    private ECPrivateKey resolveLegacyTestSigningKey(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            return generateSigningKey();
        }
        try {
            byte[] encodedKey = decodeBase64Url(configuredKey);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
            if (!(privateKey instanceof ECPrivateKey ecPrivateKey)) {
                throw new IllegalArgumentException("Configured signing key must be an EC private key");
            }
            return ecPrivateKey;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "PAY_MANDATE_SIGNING_PRIVATE_KEY must be Base64URL PKCS#8 EC key",
                    exception);
        }
    }

    private ECPrivateKey generateSigningKey() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            return (ECPrivateKey) keyPair.getPrivate();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate mandate signing key", exception);
        }
    }

    private byte[] decodeBase64Url(String encodedKey) {
        try {
            return Base64.getUrlDecoder().decode(encodedKey.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Base64URL signing key", exception);
        }
    }
}
