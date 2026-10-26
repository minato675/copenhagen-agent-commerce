package com.copenhagen.core.common.crypto;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiEncryptorTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY =
            Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private final PiiEncryptor encryptor = new PiiEncryptor(KEY);

    @Test
    @DisplayName("암호화한 값을 복호화하면 원문과 같다")
    void encryptThenDecrypt_returnsOriginalPlaintext() {
        String plaintext = "user@example.com";

        assertEquals(plaintext, encryptor.decrypt(encryptor.encrypt(plaintext)));
    }

    @Test
    @DisplayName("한글과 이모지도 왕복할 수 있다")
    void encryptThenDecrypt_multibytePlaintext_returnsOriginal() {
        String plaintext = "서울특별시 강남구 테헤란로 1길 🏠";

        assertEquals(plaintext, encryptor.decrypt(encryptor.encrypt(plaintext)));
    }

    @Test
    @DisplayName("같은 평문이라도 암호문은 매번 다르다")
    void encrypt_samePlaintextTwice_producesDifferentCiphertext() {
        String plaintext = "user@example.com";

        EncryptedPii first = encryptor.encrypt(plaintext);
        EncryptedPii second = encryptor.encrypt(plaintext);

        assertFalse(first.equals(second), "IV가 매번 새로 생성되므로 암호문이 같으면 안 된다");
        assertEquals(plaintext, encryptor.decrypt(first));
        assertEquals(plaintext, encryptor.decrypt(second));
    }

    @Test
    @DisplayName("암호문 앞 1바이트는 키 버전이고 그 뒤 12바이트는 IV다")
    void encrypt_payloadLayout_startsWithKeyVersionAndIv() {
        byte[] payload = encryptor.encrypt("user@example.com").toByteArray();

        assertEquals(0x01, payload[0]);
        assertTrue(payload.length > 1 + 12, "키 버전과 IV 뒤에 암호문이 있어야 한다");
    }

    @Test
    @DisplayName("변조된 암호문은 복호화를 거부한다")
    void decrypt_tamperedCiphertext_throwsIllegalStateException() {
        byte[] payload = encryptor.encrypt("user@example.com").toByteArray();
        payload[payload.length - 1] ^= 0x01;

        assertThrows(IllegalStateException.class, () -> encryptor.decrypt(EncryptedPii.of(payload)));
    }

    @Test
    @DisplayName("다른 키로는 복호화할 수 없다")
    void decrypt_withDifferentKey_throwsIllegalStateException() {
        EncryptedPii encrypted = encryptor.encrypt("user@example.com");
        PiiEncryptor otherEncryptor = new PiiEncryptor(OTHER_KEY);

        assertThrows(IllegalStateException.class, () -> otherEncryptor.decrypt(encrypted));
    }

    @Test
    @DisplayName("지원하지 않는 키 버전은 복호화를 거부한다")
    void decrypt_unknownKeyVersion_throwsIllegalStateException() {
        byte[] payload = encryptor.encrypt("user@example.com").toByteArray();
        payload[0] = 0x7F;

        assertThrows(IllegalStateException.class, () -> encryptor.decrypt(EncryptedPii.of(payload)));
    }

    @Test
    @DisplayName("null은 암호화·복호화 모두 null로 통과시킨다")
    void encryptAndDecrypt_null_returnsNull() {
        assertNull(encryptor.encrypt(null));
        assertNull(encryptor.decrypt(null));
    }

    @Test
    @DisplayName("32바이트가 아닌 키는 기동 시점에 거부한다")
    void constructor_keyWithWrongLength_throwsIllegalStateException() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());

        assertThrows(IllegalStateException.class, () -> new PiiEncryptor(shortKey));
    }

    @Test
    @DisplayName("비어 있거나 Base64가 아닌 키는 기동 시점에 거부한다")
    void constructor_blankOrNonBase64Key_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> new PiiEncryptor(null));
        assertThrows(IllegalStateException.class, () -> new PiiEncryptor("  "));
        assertThrows(IllegalStateException.class, () -> new PiiEncryptor("not_base64!!!"));
    }

    @Test
    @DisplayName("EncryptedPii는 값으로 비교하고 내용을 toString에 노출하지 않는다")
    void encryptedPii_comparesByValueAndHidesContent() {
        byte[] raw = {1, 2, 3};
        EncryptedPii first = EncryptedPii.of(raw);
        EncryptedPii second = EncryptedPii.of(new byte[] {1, 2, 3});

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("EncryptedPii(3 bytes)", first.toString());

        raw[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, first.toByteArray(), "생성 시점에 방어적 복사를 해야 한다");
    }
}
