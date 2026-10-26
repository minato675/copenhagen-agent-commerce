package com.copenhagen.merchantmock.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.erdtman.jcs.JsonCanonicalizer;

/**
 * RFC 8785 JCS 정규화. 같은 의미의 JSON 을 항상 같은 바이트로 만든다(DESIGN.md 6.1절).
 *
 * <p>키를 UTF-16 코드유닛 순으로 정렬하고 공백을 없애고 숫자와 문자열 표현을 고정한다.
 * 서명은 이 결과 바이트에 찍힌다. 검증할 때 다시 정규화하지 않는다(불변 조건 5번).
 *
 * <p>정규화는 순서와 표기만 건드린다. 키를 지우거나 값을 바꾸지 않는다.
 * 서명 대상에서 무엇을 뺄지는 {@link CheckoutSigningScope} 가 따로 판단한다.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /**
     * JSON 문자열을 JCS 정규화한다.
     *
     * @throws IllegalArgumentException JSON 으로 읽을 수 없을 때
     */
    public static String canonicalize(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("정규화할 JSON 이 비어 있다");
        }
        try {
            return new JsonCanonicalizer(json).getEncodedString();
        } catch (IOException exception) {
            // 라이브러리가 파싱 실패를 IOException 으로 던진다. 입력이 잘못된 것이지 입출력 문제가 아니다.
            throw new IllegalArgumentException("JCS 정규화에 실패했다. JSON 이 아니다", exception);
        }
    }

    /** 서명에 넘길 바이트. 정규화 결과는 항상 UTF-8 로 읽는다. */
    public static byte[] canonicalBytes(String json) {
        return canonicalize(json).getBytes(StandardCharsets.UTF_8);
    }
}
