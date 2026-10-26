package com.copenhagen.core.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 개인정보 평문을 AES-256-GCM으로 암호화한다.
 *
 * <p>저장 형식은 {@code [1바이트 키 버전][12바이트 IV][암호문 + 인증 태그]}다.
 * 키 버전을 페이로드 앞에 두었으므로 별도 컬럼 없이 키를 회전할 수 있다.
 *
 * <p>예외 메시지에 평문을 넣지 않는다.
 */
public class PiiEncryptor {

    /** 현재 키 버전. 키를 바꾸면 값을 올리고 복호화에서 옛 버전도 계속 지원한다. */
    private static final byte KEY_VERSION_1 = 0x01;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int HEADER_LENGTH_BYTES = 1 + IV_LENGTH_BYTES;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public PiiEncryptor(String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    public EncryptedPii encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return EncryptedPii.of(ByteBuffer.allocate(HEADER_LENGTH_BYTES + cipherText.length)
                    .put(KEY_VERSION_1)
                    .put(iv)
                    .put(cipherText)
                    .array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("개인정보 암호화에 실패했다.", exception);
        }
    }

    public String decrypt(EncryptedPii encrypted) {
        if (encrypted == null) {
            return null;
        }
        byte[] payload = encrypted.toByteArray();
        if (payload.length <= HEADER_LENGTH_BYTES) {
            throw new IllegalStateException("개인정보 암호문 길이가 유효하지 않다.");
        }
        byte keyVersion = payload[0];
        if (keyVersion != KEY_VERSION_1) {
            throw new IllegalStateException("지원하지 않는 개인정보 암호화 키 버전이다: " + keyVersion);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, payload, 1, IV_LENGTH_BYTES));
            byte[] plaintext = cipher.doFinal(payload, HEADER_LENGTH_BYTES, payload.length - HEADER_LENGTH_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("개인정보 복호화에 실패했다. 키가 다르거나 암호문이 변조됐다.", exception);
        }
    }

    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("CORE_PII_ENC_KEY가 설정되지 않았다.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("CORE_PII_ENC_KEY는 Base64 문자열이어야 한다.", exception);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "CORE_PII_ENC_KEY는 Base64로 디코딩했을 때 " + KEY_LENGTH_BYTES + "바이트여야 한다. 현재 "
                            + keyBytes.length + "바이트");
        }
        return keyBytes;
    }
}
