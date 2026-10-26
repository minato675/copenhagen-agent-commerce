package com.copenhagen.pay.common.auth;

import java.util.Optional;

@FunctionalInterface
public interface PayTokenVerifier {

    Optional<PayPrincipal> verify(String bearerToken);
}
