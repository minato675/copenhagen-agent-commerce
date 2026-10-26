package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionItemStatus;
import java.util.Optional;

public interface AuthorizationSessionItemRepository {

    Optional<AuthorizationSessionItemStatus> findItemStatus(String sessionItemId);

    boolean transitionItemStatus(
            String sessionItemId,
            AuthorizationSessionItemStatus expectedStatus,
            AuthorizationSessionItemStatus targetStatus);
}
