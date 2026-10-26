package com.copenhagen.core.common.crypto;

import java.util.Locale;

/**
 * 결정적 해시를 만들기 전에 평문을 정규화한다.
 *
 * <p>정규화를 빠뜨리면 대소문자나 공백만 다른 값이 서로 다른 해시가 되어 중복 가입이 뚫린다.
 * 암호화해서 저장하는 값도 같은 정규화를 거친 결과여야 해시와 원문이 어긋나지 않는다.
 */
public final class PiiNormalizer {

    private PiiNormalizer() {
    }

    /** 앞뒤 공백을 제거하고 소문자로 맞춘다. */
    public static String email(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 숫자와 선행 {@code +}만 남긴다. 호출자는 E.164 형식으로 넘겨야 하며,
     * 이 메서드는 구분자(공백·하이픈·괄호)만 걷어낼 뿐 국가번호를 추론하지 않는다.
     */
    public static String phone(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        boolean international = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        return international ? "+" + digits : digits;
    }
}
