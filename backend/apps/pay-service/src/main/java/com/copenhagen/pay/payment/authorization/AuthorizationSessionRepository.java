package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.util.Optional;

public interface AuthorizationSessionRepository {

    Optional<AuthorizationSessionStatus> findStatus(String authorizationSessionId);

    boolean transitionStatus(
            String authorizationSessionId,
            AuthorizationSessionStatus expectedStatus,
            AuthorizationSessionStatus targetStatus);
}
