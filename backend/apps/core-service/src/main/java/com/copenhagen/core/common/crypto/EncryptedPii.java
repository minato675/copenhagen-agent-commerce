package com.copenhagen.core.common.crypto;

import java.util.Arrays;

/**
 * 암호화된 개인정보 바이트열(BYTEA 컬럼 값)을 감싸는 값 객체.
 *
 * <p>record의 {@code byte[]} 필드는 배열 항등성 때문에 equals/hashCode가 참조 비교로 동작한다.
 * 도메인 레코드가 이 타입을 쓰면 값 비교가 의도대로 되고, toString이 내용을 흘리지 않는다.
 */
public final class EncryptedPii {

    private final byte[] value;

    private EncryptedPii(byte[] value) {
        this.value = value;
    }

    /** {@code value}가 null이면 null을 돌려준다. NULL 허용 컬럼을 그대로 다루기 위한 것이다. */
    public static EncryptedPii of(byte[] value) {
        return value == null ? null : new EncryptedPii(value.clone());
    }

    public byte[] toByteArray() {
        return value.clone();
    }

    public int length() {
        return value.length;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EncryptedPii otherPii)) {
            return false;
        }
        return Arrays.equals(value, otherPii.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    /** 암호문 내용은 절대 노출하지 않는다. 로그에 찍혀도 길이만 남는다. */
    @Override
    public String toString() {
        return "EncryptedPii(" + value.length + " bytes)";
    }
}
