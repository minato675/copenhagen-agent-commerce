package com.copenhagen.core.auth.oauthaccount;

public enum OauthProvider {

    GOOGLE("GOOGLE");

    private final String value;

    OauthProvider(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * 저장 값은 대문자지만, OAuth 제공자 응답이나 요청 파라미터는 소문자로 오는 경우가 있어
     * 대소문자를 구분하지 않고 역매핑한다.
     */
    public static OauthProvider from(String value) {
        for (OauthProvider provider : values()) {
            if (provider.value.equalsIgnoreCase(value)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unknown OAuth provider: " + value);
    }
}
