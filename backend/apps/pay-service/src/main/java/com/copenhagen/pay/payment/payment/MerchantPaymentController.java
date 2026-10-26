package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyKey;
import com.copenhagen.pay.common.idempotency.RequestHashGenerator;
import com.copenhagen.pay.common.validation.RequestValidation;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.copenhagen.pay.payment.policy.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/merchant/v1/payments")
public class MerchantPaymentController {

    private final MerchantPaymentService service;
    private final RequestHashGenerator requestHashGenerator;

    public MerchantPaymentController(
            MerchantPaymentService service,
            RequestHashGenerator requestHashGenerator) {
        this.service = service;
        this.requestHashGenerator = requestHashGenerator;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            HttpServletRequest httpRequest) {
        RequestValidation.requireLengthAtMost(requestId, "X-Request-Id", 100);
        PayRequestContext.requireMerchantPrincipal(httpRequest, request.merchantId());
        IdempotencyKey idempotencyKey = PayRequestContext.findIdempotencyKey(httpRequest)
                .orElseThrow(() -> new PayException(PayErrorCode.IDEMPOTENCY_KEY_REQUIRED));
        String requestHash = PayRequestContext.findRequestHash(httpRequest)
                .orElseGet(() -> requestHashGenerator.generate(httpRequest, request));

        MerchantPaymentService.PaymentResult result = service.create(
                request.toCommand(idempotencyKey.value(), requestHash));
        return ResponseEntity.ok(PaymentResponse.from(result));
    }

    public record PaymentRequest(
            @NotBlank @Size(max = 100) String merchantId,
            @NotBlank @Size(max = 150) String businessCheckoutId,
            @NotBlank @Size(max = 100) String checkoutHash,
            @PositiveOrZero long amountMinor,
            @NotBlank @Pattern(regexp = "KRW") String currency,
            @NotBlank @Size(max = 36) String credentialId) {

        private MerchantPaymentService.CreateCommand toCommand(
                String idempotencyKey,
                String requestHash) {
            return new MerchantPaymentService.CreateCommand(
                    merchantId,
                    businessCheckoutId,
                    checkoutHash,
                    amountMinor,
                    currency,
                    credentialId,
                    idempotencyKey,
                    requestHash);
        }

        @Override
        public String toString() {
            return "PaymentRequest["
                    + "merchantId='" + merchantId + '\''
                    + ", businessCheckoutId='" + businessCheckoutId + '\''
                    + ", checkoutHash=<redacted>"
                    + ", amountMinor=" + amountMinor
                    + ", currency='" + currency + '\''
                    + ", credentialId=<redacted>"
                    + ']';
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentResponse(
            String paymentId,
            String status,
            long amountMinor,
            String currency,
            String pgTransactionId,
            String errorCode,
            String nextAction) {

        private static PaymentResponse from(MerchantPaymentService.PaymentResult result) {
            PaymentStatus status = result.status();
            return new PaymentResponse(
                    result.payment().id(),
                    status.name(),
                    result.payment().amountMinor(),
                    result.payment().currency(),
                    status == PaymentStatus.SUCCESS
                            ? result.execution().pgTransactionId()
                            : null,
                    status == PaymentStatus.FAILED ? "PG_DECLINED" : null,
                    nextAction(status));
        }

        private static String nextAction(PaymentStatus status) {
            return switch (status) {
                case SUCCESS -> "CREATE_ORDER";
                case FAILED -> "SHOW_PAYMENT_FAILURE";
                case UNKNOWN -> "WAIT_FOR_PAYMENT_STATUS";
                case READY -> "EXECUTE_PAYMENT";
                case PROCESSING -> "WAIT_FOR_PAYMENT_STATUS";
            };
        }
    }
}
