package com.copenhagen.pay.payment.credential.mandate;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class ArtifactEncryptionService {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String FORMAT_VERSION = "v1";

    private final SecretKeySpec key;
    private final SecureRandom secureRandom;

    public ArtifactEncryptionService(MandateProperties properties) {
        this.key = new SecretKeySpec(resolveKey(properties.encryptionKey()), KEY_ALGORITHM);
        this.secureRandom = new SecureRandom();
    }

    public EncryptedArtifact encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String encoded = String.join(
                    ".",
                    FORMAT_VERSION,
                    encode(nonce),
                    encode(ciphertext));
            return new EncryptedArtifact(encoded, sha256Hex(plaintext));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Artifact encryption failed", exception);
        }
    }

    public String decrypt(String encodedArtifact) {
        if (encodedArtifact == null) {
            throw new IllegalArgumentException("encodedArtifact must not be null");
        }
        String[] parts = encodedArtifact.split("\\.", -1);
        if (parts.length != 3 || !FORMAT_VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("Unsupported encrypted artifact format");
        }
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[2]);
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("Invalid artifact nonce");
            }
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Artifact decryption failed", exception);
        }
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private byte[] resolveKey(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            byte[] generatedKey = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(generatedKey);
            return generatedKey;
        }
        try {
            byte[] decodedKey = Base64.getUrlDecoder().decode(configuredKey);
            if (decodedKey.length != KEY_BYTES) {
                throw new IllegalArgumentException("PAY_MANDATE_ENCRYPTION_KEY must be 32 bytes");
            }
            return decodedKey;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PAY_MANDATE_ENCRYPTION_KEY must be Base64URL", exception);
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record EncryptedArtifact(
            String ciphertext,
            String hash) {
    }
}
