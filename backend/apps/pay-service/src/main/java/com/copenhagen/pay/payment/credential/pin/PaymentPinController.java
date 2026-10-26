package com.copenhagen.pay.payment.credential.pin;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyKey;
import com.copenhagen.pay.common.idempotency.RequestHashGenerator;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/pay/v1/users/me/payment-pin")
public class PaymentPinController {

    private final PaymentPinService service;
    private final RequestHashGenerator requestHashGenerator;

    public PaymentPinController(PaymentPinService service) {
        this(service, new RequestHashGenerator(new ObjectMapper()));
    }

    @Autowired
    public PaymentPinController(
            PaymentPinService service,
            RequestHashGenerator requestHashGenerator) {
        this.service = service;
        this.requestHashGenerator = requestHashGenerator;
    }

    @GetMapping
    public ResponseEntity<PaymentPinStatusResponse> status(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(service.status(userId(httpRequest)));
    }

    @PostMapping
    public ResponseEntity<Void> register(
            @Valid @RequestBody RegisterPaymentPinRequest request,
            HttpServletRequest httpRequest) {
        IdempotencyKey idempotencyKey = PayRequestContext.findIdempotencyKey(httpRequest)
                .orElseThrow(() -> new PayException(PayErrorCode.IDEMPOTENCY_KEY_REQUIRED));
        String requestHash = PayRequestContext.findRequestHash(httpRequest)
                .orElseGet(() -> requestHashGenerator.generate(httpRequest, request));
        service.register(userId(httpRequest), request.pin(), idempotencyKey.value(), requestHash);
        return ResponseEntity.noContent().build();
    }

    @PutMapping
    public ResponseEntity<Void> change(
            @Valid @RequestBody ChangePaymentPinRequest request,
            HttpServletRequest httpRequest) {
        service.change(userId(httpRequest), request.currentPin(), request.newPin());
        return ResponseEntity.noContent().build();
    }

    private String userId(HttpServletRequest request) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(request);
        return principal.subject();
    }

    public record RegisterPaymentPinRequest(
            @NotBlank
            @Pattern(regexp = "\\d{6}")
            String pin) {

        @Override
        public String toString() {
            return "RegisterPaymentPinRequest[pin=<redacted>]";
        }
    }

    public record ChangePaymentPinRequest(
            @NotBlank
            @Pattern(regexp = "\\d{6}")
            String currentPin,
            @NotBlank
            @Pattern(regexp = "\\d{6}")
            String newPin) {

        @Override
        public String toString() {
            return "ChangePaymentPinRequest[currentPin=<redacted>, newPin=<redacted>]";
        }
    }
}
