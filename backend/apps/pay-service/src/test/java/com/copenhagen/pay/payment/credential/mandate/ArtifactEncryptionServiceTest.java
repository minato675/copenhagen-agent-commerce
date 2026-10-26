package com.copenhagen.pay.payment.credential.mandate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ArtifactEncryptionServiceTest {

    @Test
    void encryptsAndDecryptsArtifactWithoutStoringPlaintext() {
        ArtifactEncryptionService service = new ArtifactEncryptionService(
                new MandateProperties("pay-service", "test-key", "", ""));

        ArtifactEncryptionService.EncryptedArtifact encrypted = service.encrypt(
                "{\"credentialId\":\"credential-001\"}");

        assertNotEquals("{\"credentialId\":\"credential-001\"}", encrypted.ciphertext());
        assertEquals(
                "{\"credentialId\":\"credential-001\"}",
                service.decrypt(encrypted.ciphertext()));
        assertEquals(
                ArtifactEncryptionService.sha256Hex("{\"credentialId\":\"credential-001\"}"),
                encrypted.hash());
    }
}
