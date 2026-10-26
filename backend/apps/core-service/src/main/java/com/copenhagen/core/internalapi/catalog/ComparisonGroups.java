package com.copenhagen.core.internalapi.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 내부 계약이 요구하는 안정적인 식별자를 상품명·옵션 그룹명에서 만든다(설계서 §4.1·§4.2).
 *
 * <p>Merchant Catalog에는 비교 묶음 id도 옵션 그룹 id도 없다. 같은 이름을 파는 여러 merchant의 offer가
 * 한 묶음으로 합쳐지려면 이름에서 결정적으로 계산한 값이어야 하고, mcp-server의 id 패턴
 * ({@code [A-Za-z0-9_-]{1,96}})도 만족해야 해서 해시 앞자리를 hex로 쓴다.
 */
public final class ComparisonGroups {

    private static final int GROUP_ID_LENGTH = 12;
    private static final int OPTION_GROUP_ID_LENGTH = 8;

    private ComparisonGroups() {
    }

    /** 공백을 지우고 소문자로 맞춘 이름이 같으면 같은 묶음이다. */
    public static String normalize(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public static String comparisonGroupId(String name) {
        return "cg_" + sha256Hex(normalize(name)).substring(0, GROUP_ID_LENGTH);
    }

    public static String optionGroupId(String name) {
        return "opt_" + sha256Hex(name == null ? "" : name).substring(0, OPTION_GROUP_ID_LENGTH);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }
}
