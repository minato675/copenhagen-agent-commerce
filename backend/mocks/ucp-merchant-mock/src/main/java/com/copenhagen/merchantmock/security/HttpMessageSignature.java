package com.copenhagen.merchantmock.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.impl.ECDSA;
import com.nimbusds.jose.jwk.ECKey;

/**
 * RFC 9421 HTTP Message Signature 와 RFC 9530 Content-Digest(DESIGN.md 6.6절). 웹훅 발송이 쓴다.
 *
 * <p>UCP Order 명세가 정한 대로 서명 대상은 다섯 컴포넌트, 파라미터는 {@code created} 와 {@code keyid} 둘이다.
 * {@code alg} 는 넣지 않는다. UCP 는 JWK 의 kty·crv 로 알고리즘을 정한다. 서명 바이트는 R‖S 64바이트다.
 * JCA 가 내는 DER 을 Nimbus 로 바꾼다. 6.4절 Compact JWS 와 같은 규칙이다.
 *
 * <p>검증도 여기 둔다. 내 테스트가 내 서명을 공개키로 되풀이해 확인하고, Core 가 검증기를 짤 때 참조한다.
 */
public final class HttpMessageSignature {

    public static final String LABEL = "sig1";
    public static final List<String> COVERED_COMPONENTS =
            List.of("@method", "@authority", "@path", "content-digest", "content-type");

    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final int CONCAT_SIGNATURE_LENGTH = 64;
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();
    private static final Pattern SIGNATURE_INPUT = Pattern.compile(
            "^" + LABEL + "=\\((?<components>[^)]*)\\);created=(?<created>\\d+);keyid=\"(?<keyid>[^\"]+)\"$");
    private static final Pattern SIGNATURE = Pattern.compile("^" + LABEL + "=:(?<value>[A-Za-z0-9+/=]+):$");

    private HttpMessageSignature() {
    }

    /** 서명 대상 컴포넌트 값 다섯. {@code authority} 는 host[:port], {@code path} 는 경로만이다. */
    public record Components(String method, String authority, String path, String contentDigest,
            String contentType) {
    }

    /** {@code Signature-Input} 과 {@code Signature} 헤더 값. */
    public record Signed(String signatureInput, String signature) {
    }

    /** RFC 9530. {@code sha-256=:<base64>:}. 입력은 실제로 보낼 본문 바이트 그대로다. */
    public static String contentDigest(byte[] body) {
        return "sha-256=:" + ENCODER.encodeToString(sha256(body)) + ":";
    }

    /** {@code ("@method" ... "content-type");created=...;keyid="..."}. Signature-Input 의 라벨 뒤 부분이다. */
    public static String signatureParams(long created, String keyId) {
        StringBuilder params = new StringBuilder("(");
        for (int i = 0; i < COVERED_COMPONENTS.size(); i++) {
            if (i > 0) {
                params.append(' ');
            }
            params.append('"').append(COVERED_COMPONENTS.get(i)).append('"');
        }
        return params.append(");created=").append(created)
                .append(";keyid=\"").append(keyId).append('"')
                .toString();
    }

    /** RFC 9421 §2.5 서명 베이스. 컴포넌트 한 줄씩, 마지막에 {@code @signature-params}. 개행은 LF 다. */
    public static String signatureBase(Components components, String signatureParams) {
        return String.join("\n",
                "\"@method\": " + components.method(),
                "\"@authority\": " + components.authority(),
                "\"@path\": " + components.path(),
                "\"content-digest\": " + components.contentDigest(),
                "\"content-type\": " + components.contentType(),
                "\"@signature-params\": " + signatureParams);
    }

    /**
     * 서명한다. {@code created} 는 unix 초다. 발송할 때마다 새로 부른다(불변 조건 7번).
     *
     * @param key 개인키가 든 EC JWK. keyid 는 이 키의 kid 다
     */
    public static Signed sign(Components components, long created, ECKey key) {
        String params = signatureParams(created, key.getKeyID());
        byte[] base = signatureBase(components, params).getBytes(StandardCharsets.UTF_8);
        try {
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(key.toECPrivateKey());
            signer.update(base);
            byte[] concat = ECDSA.transcodeSignatureToConcat(signer.sign(), CONCAT_SIGNATURE_LENGTH);
            return new Signed(LABEL + "=" + params, LABEL + "=:" + ENCODER.encodeToString(concat) + ":");
        } catch (GeneralSecurityException | JOSEException exception) {
            throw new IllegalStateException("RFC 9421 서명에 실패했다", exception);
        }
    }

    /**
     * 검증한다. 헤더 두 값을 그대로 받아 베이스를 다시 만들고 공개키로 확인한다.
     *
     * <p>컴포넌트 목록이 내가 쓰는 다섯과 다르거나, keyid 가 키의 kid 와 다르거나, 형식이 어긋나면 false 다.
     * 예외를 내지 않는다. 검증 실패는 예외가 아니라 결과다.
     */
    public static boolean verify(Components components, String signatureInput, String signature,
            ECKey publicKey) {
        if (signatureInput == null || signature == null || publicKey == null) {
            return false;
        }
        Matcher input = SIGNATURE_INPUT.matcher(signatureInput);
        Matcher value = SIGNATURE.matcher(signature);
        if (!input.matches() || !value.matches()) {
            return false;
        }
        if (!expectedComponentList().equals(input.group("components"))
                || !input.group("keyid").equals(publicKey.getKeyID())) {
            return false;
        }
        String params = signatureInput.substring(LABEL.length() + 1);
        byte[] base = signatureBase(components, params).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] der = ECDSA.transcodeSignatureToDER(DECODER.decode(value.group("value")));
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey.toECPublicKey());
            verifier.update(base);
            return verifier.verify(der);
        } catch (GeneralSecurityException | JOSEException | IllegalArgumentException exception) {
            return false;
        }
    }

    /** Signature-Input 에서 created 를 읽는다. 검증하는 쪽이 오차를 두고 싶을 때 쓴다. 형식이 틀리면 -1. */
    public static long createdOf(String signatureInput) {
        if (signatureInput == null) {
            return -1;
        }
        Matcher input = SIGNATURE_INPUT.matcher(signatureInput);
        return input.matches() ? Long.parseLong(input.group("created")) : -1;
    }

    private static String expectedComponentList() {
        return String.join(" ", COVERED_COMPONENTS.stream().map(name -> "\"" + name + "\"").toList());
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", exception);
        }
    }
}
