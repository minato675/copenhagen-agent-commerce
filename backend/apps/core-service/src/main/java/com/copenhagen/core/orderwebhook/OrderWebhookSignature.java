package com.copenhagen.core.orderwebhook;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.ucp.CoreUcpProperties;
import com.nimbusds.jose.crypto.impl.ECDSA;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;

/** Merchant의 RFC 9421 ES256 서명과 원문 바이트의 SHA-256 digest를 검증한다. */
@Component
public class OrderWebhookSignature {

    private static final String COMPONENTS =
            "\"@method\" \"@authority\" \"@path\" \"content-digest\" \"content-type\"";
    private static final Pattern INPUT = Pattern.compile(
            "^sig1=\\(([^)]*)\\);created=(\\d{1,12});keyid=\"([^\"]{1,128})\"$");
    private static final Pattern VALUE = Pattern.compile("^sig1=:([A-Za-z0-9+/=]+):$");
    private final URI target;
    private final Clock clock;

    public OrderWebhookSignature(CoreUcpProperties properties, Clock clock) {
        this.target = URI.create(properties.orderWebhookUrl());
        this.clock = clock;
    }

    public String verify(byte[] payload, HttpHeaders headers, List<ECKey> keys) {
        try {
            String input = headers.getFirst("Signature-Input");
            String signature = headers.getFirst("Signature");
            Matcher parameters = INPUT.matcher(input == null ? "" : input);
            Matcher value = VALUE.matcher(signature == null ? "" : signature);
            if (!parameters.matches() || !value.matches() || !COMPONENTS.equals(parameters.group(1))) {
                throw rejected();
            }
            long created = Long.parseLong(parameters.group(2));
            if (Math.abs(clock.instant().getEpochSecond() - created) > 300) {
                throw rejected();
            }
            String digest = "sha-256=:" + Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(payload)) + ":";
            if (!digest.equals(headers.getFirst("Content-Digest"))) {
                throw rejected();
            }
            String base = String.join("\n", "\"@method\": POST", "\"@authority\": " + target.getRawAuthority(),
                    "\"@path\": " + target.getRawPath(), "\"content-digest\": " + digest,
                    "\"content-type\": " + headers.getFirst(HttpHeaders.CONTENT_TYPE),
                    "\"@signature-params\": " + input.substring("sig1=".length()));
            byte[] signatureBytes = Base64.getDecoder().decode(value.group(1));
            if (signatureBytes.length != 64) {
                throw rejected();
            }
            for (ECKey key : keys) {
                if (!key.isPrivate() && Curve.P_256.equals(key.getCurve())
                        && parameters.group(3).equals(key.getKeyID())) {
                    Signature verifier = Signature.getInstance("SHA256withECDSA");
                    verifier.initVerify(key.toECPublicKey());
                    verifier.update(base.getBytes(StandardCharsets.UTF_8));
                    if (verifier.verify(ECDSA.transcodeSignatureToDER(signatureBytes))) {
                        return digest;
                    }
                }
            }
        } catch (CoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected();
        }
        throw rejected();
    }

    private static CoreException rejected() {
        return new CoreException(CoreErrorCode.WEBHOOK_SIGNATURE_INVALID);
    }
}
