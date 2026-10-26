package com.copenhagen.pay.payment.merchant;

import com.copenhagen.pay.payment.credential.mandate.MandateKeySet;
import com.nimbusds.jose.jwk.ECKey;
import java.util.List;

public record PayProfileResponse(
        String issuer,
        List<PublicJwk> keys) {

    public PayProfileResponse {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    public static PayProfileResponse from(MandateKeySet keySet) {
        return new PayProfileResponse(
                keySet.issuer(),
                keySet.publicKeys().stream()
                        .map(PublicJwk::from)
                        .toList());
    }

    public record PublicJwk(
            String kid,
            String kty,
            String use,
            String alg,
            String crv,
            String x,
            String y) {

        private static PublicJwk from(ECKey key) {
            return new PublicJwk(
                    key.getKeyID(),
                    "EC",
                    "sig",
                    "ES256",
                    "P-256",
                    key.getX().toString(),
                    key.getY().toString());
        }
    }
}
