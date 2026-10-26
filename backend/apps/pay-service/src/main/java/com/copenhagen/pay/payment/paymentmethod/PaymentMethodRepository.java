package com.copenhagen.pay.payment.paymentmethod;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository {

    List<PaymentMethod> findActiveByUserId(String userId);

    Optional<PaymentMethod> findActiveByIdAndUserIdForUpdate(
            String paymentMethodId,
            String userId);

    void insert(PaymentMethod paymentMethod);

    int clearDefaultForUser(String userId);

    int markDefault(String paymentMethodId, String userId);

    int softDelete(String paymentMethodId, String userId);
}
