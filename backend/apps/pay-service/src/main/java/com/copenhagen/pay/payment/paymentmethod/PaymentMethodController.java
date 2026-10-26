package com.copenhagen.pay.payment.paymentmethod;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pay/v1/payment-methods/cards")
public class PaymentMethodController {

    private final PaymentMethodService service;

    public PaymentMethodController(PaymentMethodService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentMethodResponse> register(
            @Valid @RequestBody RegisterCardRequest request,
            HttpServletRequest httpRequest) {
        PaymentMethod paymentMethod = service.register(
                userId(httpRequest),
                request.tokenizationResult().provider(),
                request.tokenizationResult().providerTokenRef(),
                request.tokenizationResult().brand(),
                request.tokenizationResult().last4(),
                request.tokenizationResult().expiryMonth(),
                request.tokenizationResult().expiryYear());
        return ResponseEntity.status(201).body(PaymentMethodResponse.from(paymentMethod));
    }

    @GetMapping
    public ResponseEntity<PaymentMethodListResponse> findActive(
            HttpServletRequest httpRequest) {
        List<PaymentMethodResponse> items = service.findActive(userId(httpRequest)).stream()
                .map(PaymentMethodResponse::from)
                .toList();
        return ResponseEntity.ok(new PaymentMethodListResponse(items));
    }

    @PatchMapping("/{paymentMethodId}/default")
    public ResponseEntity<PaymentMethodResponse> markDefault(
            @PathVariable String paymentMethodId,
            HttpServletRequest httpRequest) {
        PaymentMethod paymentMethod = service.markDefault(userId(httpRequest), paymentMethodId);
        return ResponseEntity.ok(PaymentMethodResponse.from(paymentMethod));
    }

    @DeleteMapping("/{paymentMethodId}")
    public ResponseEntity<Void> delete(
            @PathVariable String paymentMethodId,
            HttpServletRequest httpRequest) {
        service.delete(userId(httpRequest), paymentMethodId);
        return ResponseEntity.noContent().build();
    }

    private String userId(HttpServletRequest request) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(request);
        return principal.subject();
    }

    public record RegisterCardRequest(
            @NotNull @Valid TokenizationResult tokenizationResult) {

        @Override
        public String toString() {
            return "RegisterCardRequest[tokenizationResult=<redacted>]";
        }
    }

    public record TokenizationResult(
            @NotBlank @Size(max = 50) String provider,
            @NotBlank @Size(max = 255) String providerTokenRef,
            @Size(max = 30) String brand,
            @Pattern(regexp = "\\d{4}") String last4,
            @Min(1) @Max(12) Integer expiryMonth,
            @Min(0) @Max(9999) Integer expiryYear) {

        @Override
        public String toString() {
            return "TokenizationResult[provider='" + provider
                    + "', providerTokenRef=<redacted>]";
        }
    }

    public record PaymentMethodResponse(
            String paymentMethodId,
            String provider,
            String brand,
            String last4,
            Integer expiryMonth,
            Integer expiryYear,
            boolean isDefault,
            String status) {

        private static PaymentMethodResponse from(PaymentMethod paymentMethod) {
            return new PaymentMethodResponse(
                    paymentMethod.id(),
                    paymentMethod.provider(),
                    paymentMethod.brand(),
                    paymentMethod.last4(),
                    paymentMethod.expiryMonth(),
                    paymentMethod.expiryYear(),
                    paymentMethod.defaultMethod(),
                    paymentMethod.status().name());
        }
    }

    public record PaymentMethodListResponse(List<PaymentMethodResponse> items) {

        public PaymentMethodListResponse {
            items = List.copyOf(items);
        }
    }
}
