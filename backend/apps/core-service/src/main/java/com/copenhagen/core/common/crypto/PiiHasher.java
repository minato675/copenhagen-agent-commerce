package com.copenhagen.core.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 개인정보의 조회·중복확인용 결정적 해시를 만든다.
 *
 * <p>{@code HMAC-SHA256(pepper, 정규화된 평문)}을 소문자 hex 64자로 돌려준다.
 * 평문 SHA-256이 아니라 pepper를 쓰는 이유는, 해시 컬럼만 유출됐을 때 사전 공격으로
 * 이메일·전화번호를 역산하지 못하게 하기 위해서다.
 *
 * <p>정규화를 호출자에게 맡기면 빠뜨리기 쉬우므로 {@link #hashEmail}/{@link #hashPhone}이 직접 수행한다.
 */
public class PiiHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_PEPPER_LENGTH = 32;

    private final SecretKeySpec pepperKey;

    public PiiHasher(String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("CORE_PII_HASH_PEPPER가 설정되지 않았다.");
        }
        if (pepper.length() < MINIMUM_PEPPER_LENGTH) {
            throw new IllegalStateException(
                    "CORE_PII_HASH_PEPPER는 " + MINIMUM_PEPPER_LENGTH + "자 이상이어야 한다. 현재 " + pepper.length() + "자");
        }
        this.pepperKey = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hashEmail(String email) {
        return hash(PiiNormalizer.email(email));
    }

    public String hashPhone(String phone) {
        return hash(PiiNormalizer.phone(phone));
    }

    private String hash(String normalized) {
        if (normalized == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(pepperKey);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("개인정보 해시 생성에 실패했다.", exception);
        }
    }
}
