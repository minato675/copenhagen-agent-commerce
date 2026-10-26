package com.copenhagen.pay.payment.credential.biometric;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class BiometricAssertionVerifier {

    public boolean verify(
            String publicKey,
            String challenge,
            String assertion) {
        try {
            byte[] publicKeyBytes = Base64.getUrlDecoder().decode(publicKey);
            byte[] assertionBytes = Base64.getUrlDecoder().decode(assertion);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PublicKey key = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(challenge.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(assertionBytes);
        } catch (Exception exception) {
            return false;
        }
    }
}
