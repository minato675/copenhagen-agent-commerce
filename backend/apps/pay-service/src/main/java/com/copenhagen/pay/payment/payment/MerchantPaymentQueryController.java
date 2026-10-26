package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/merchant/v1/payments")
public class MerchantPaymentQueryController {

    private final MerchantPaymentQueryService service;

    public MerchantPaymentQueryController(MerchantPaymentQueryService service) {
        this.service = service;
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentStatusResponse> find(
            @PathVariable String paymentId,
            HttpServletRequest httpRequest) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(httpRequest);
        PayRequestContext.requireMerchantPrincipal(httpRequest, principal.subject());
        MerchantPaymentQueryRepository.PaymentView payment = service.find(
                principal.subject(),
                paymentId);
        return ResponseEntity.ok(PaymentStatusResponse.from(payment));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PaymentStatusResponse(
            String paymentId,
            String status,
            long amountMinor,
            String currency,
            String nextAction) {

        private static PaymentStatusResponse from(
                MerchantPaymentQueryRepository.PaymentView payment) {
            return new PaymentStatusResponse(
                    payment.paymentId(),
                    payment.status().name(),
                    payment.amountMinor(),
                    payment.currency(),
                    nextAction(payment.status()));
        }

        private static String nextAction(
                com.copenhagen.pay.payment.policy.PaymentStatus status) {
            return switch (status) {
                case READY, PROCESSING, UNKNOWN -> "WAIT_FOR_PAYMENT_STATUS";
                case SUCCESS, FAILED -> null;
            };
        }
    }
}
