package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentApprovalStatusRepository {

    Optional<PaymentApprovalStatusView> findByApprovalId(String approvalId);

    record PaymentApprovalStatusView(
            String approvalId,
            AuthorizationSessionStatus approvalStatus,
            long totalAmountMinor,
            String currency,
            List<ItemView> items) {

        public PaymentApprovalStatusView {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** 판매처 한 곳. credential 과 결제는 아직 없을 수 있다. */
    record ItemView(
            String merchantId,
            String checkoutId,
            String credentialId,
            Instant credentialExpiresAt,
            String paymentId,
            PaymentStatus paymentStatus,
            String paymentLabel) {
    }
}
