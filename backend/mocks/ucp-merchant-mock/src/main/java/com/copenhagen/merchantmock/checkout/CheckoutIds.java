package com.copenhagen.merchantmock.checkout;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 체크아웃 id 를 만든다.
 *
 * <p>Core 가 준 Idempotency-Key 에서 UUIDv5 를 만든다(DESIGN.md 8.1절). 같은 키면 같은 id 가 나와
 * INSERT 가 PK 에 걸리고 기존 행을 돌려준다. 키를 저장할 컬럼 없이 재시도를 흡수한다. 키가 없으면 랜덤이다.
 */
final class CheckoutIds {

    /** RFC 4122 의 URL 네임스페이스. 값 자체에 의미는 없고 고정돼 있으면 된다. */
    private static final UUID NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private CheckoutIds() {
    }

    static String fromIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return v5(NAMESPACE, idempotencyKey.trim()).toString();
    }

    static UUID v5(UUID namespace, String name) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 을 쓸 수 없다", exception);
        }
        ByteBuffer ns = ByteBuffer.allocate(16);
        ns.putLong(namespace.getMostSignificantBits());
        ns.putLong(namespace.getLeastSignificantBits());
        sha1.update(ns.array());
        sha1.update(name.getBytes(StandardCharsets.UTF_8));
        byte[] hash = sha1.digest();

        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);

        ByteBuffer buffer = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
