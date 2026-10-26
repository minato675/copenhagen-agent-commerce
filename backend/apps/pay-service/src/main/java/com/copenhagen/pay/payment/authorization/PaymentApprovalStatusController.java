package com.copenhagen.pay.payment.authorization;

import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/payment-approvals")
public class PaymentApprovalStatusController {

    private static final String CORE_SERVICE_SUBJECT = "core-service";

    private final PaymentApprovalStatusService service;

    public PaymentApprovalStatusController(PaymentApprovalStatusService service) {
        this.service = service;
    }

    @GetMapping("/{approvalId}/status")
    public ResponseEntity<PaymentApprovalStatusResponse> find(
            @PathVariable String approvalId,
            HttpServletRequest httpRequest) {
        requireCoreService(httpRequest);
        return ResponseEntity.ok(service.find(approvalId));
    }

    private void requireCoreService(HttpServletRequest httpRequest) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(httpRequest);
        boolean isCoreService = principal.isCoreService()
                || principal.type() == PayPrincipal.Type.SERVICE
                && CORE_SERVICE_SUBJECT.equals(principal.subject());
        if (!isCoreService) {
            throw new PayException(PayErrorCode.FORBIDDEN);
        }
    }
}
