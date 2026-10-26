package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyKey;
import com.copenhagen.pay.common.idempotency.RequestHashGenerator;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.copenhagen.pay.payment.credential.biometric.BiometricChallengeService;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class AuthorizationSessionController {

    private final AuthorizationSessionService service;
    private final AuthorizationApprovalService approvalService;
    private final RequestHashGenerator requestHashGenerator;

    public AuthorizationSessionController(
            AuthorizationSessionService service,
            AuthorizationApprovalService approvalService,
            RequestHashGenerator requestHashGenerator) {
        this.service = service;
        this.approvalService = approvalService;
        this.requestHashGenerator = requestHashGenerator;
    }

    @PostMapping("/internal/v1/payment-approvals")
    public ResponseEntity<ApprovalCreatedResponse> create(
            @Valid @RequestBody CreateAuthorizationSessionRequest request,
            HttpServletRequest httpRequest) {
        IdempotencyKey idempotencyKey = PayRequestContext.findIdempotencyKey(httpRequest)
                .orElseThrow(() -> new PayException(PayErrorCode.IDEMPOTENCY_KEY_REQUIRED));
        String requestHash = PayRequestContext.findRequestHash(httpRequest)
                .orElseGet(() -> requestHashGenerator.generate(httpRequest, request));
        AuthorizationSessionService.CreateResult result = service.create(
                request.toCommand(),
                idempotencyKey.value(),
                requestHash);
        ApprovalCreatedResponse response = ApprovalCreatedResponse.from(result.session());
        return result.replay()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(201).body(response);
    }

    @GetMapping("/api/pay/v1/payment-approvals")
    public ResponseEntity<ApprovalListResponse> findForUser(
            @RequestParam(required = false) String status,
            HttpServletRequest httpRequest) {
        String userId = userId(httpRequest);
        AuthorizationSessionStatus requestedStatus = parseStatus(status);
        List<ApprovalListItemResponse> items = service.findForUser(userId, requestedStatus).stream()
                .map(ApprovalListItemResponse::from)
                .toList();
        return ResponseEntity.ok(new ApprovalListResponse(items));
    }

    @GetMapping("/api/pay/v1/payment-approvals/{approvalId}")
    public ResponseEntity<ApprovalDetailResponse> findDetail(
            @PathVariable String approvalId,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
            HttpServletRequest httpRequest) {
        AuthorizationSession session = service.findDetail(userId(httpRequest), approvalId);
        AuthorizationApprovalService.PreparedDetail prepared = approvalService.prepareDetail(
                session,
                deviceId);
        List<MerchantResponse> merchants = prepared.session().items().stream()
                .map(item -> MerchantResponse.from(item, service.displaySnapshot(item)))
                .toList();
        return ResponseEntity.ok(ApprovalDetailResponse.from(
                prepared.session(),
                merchants,
                prepared.challenge().map(AuthorizationSessionController::toResponse).orElse(null)));
    }

    @PatchMapping("/api/pay/v1/payment-approvals/{approvalId}/payment-method")
    public ResponseEntity<PaymentMethodSelectionResponse> selectPaymentMethod(
            @PathVariable String approvalId,
            @Valid @RequestBody PaymentMethodSelectionRequest request,
            HttpServletRequest httpRequest) {
        requireIdempotencyKey(httpRequest);
        AuthorizationSession session = service.selectPaymentMethod(
                userId(httpRequest),
                approvalId,
                request.paymentMethodId());
        return ResponseEntity.ok(PaymentMethodSelectionResponse.from(session));
    }

    @PostMapping("/api/pay/v1/payment-approvals/{approvalId}/approve")
    public ResponseEntity<ApprovalActionResponse> approve(
            @PathVariable String approvalId,
            @Valid @RequestBody ApproveRequest request,
            HttpServletRequest httpRequest) {
        requireIdempotencyKey(httpRequest);
        AuthorizationApprovalService.ApprovalResult result = approvalService.approve(
                userId(httpRequest),
                approvalId,
                request.toCommand());
        return ResponseEntity.ok(ApprovalActionResponse.from(result));
    }

    @PostMapping("/api/pay/v1/payment-approvals/{approvalId}/reject")
    public ResponseEntity<ApprovalActionResponse> reject(
            @PathVariable String approvalId,
            @Valid @RequestBody RejectRequest request,
            HttpServletRequest httpRequest) {
        requireIdempotencyKey(httpRequest);
        AuthorizationApprovalService.ApprovalResult result = approvalService.reject(
                userId(httpRequest),
                approvalId);
        return ResponseEntity.ok(ApprovalActionResponse.from(result));
    }

    private String userId(HttpServletRequest request) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(request);
        return principal.subject();
    }

    private void requireIdempotencyKey(HttpServletRequest request) {
        PayRequestContext.findIdempotencyKey(request)
                .orElseThrow(() -> new PayException(PayErrorCode.IDEMPOTENCY_KEY_REQUIRED));
    }

    private AuthorizationSessionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AuthorizationSessionStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    "승인 상태 값이 올바르지 않습니다.",
                    null,
                    "CHECK_STATUS",
                    exception);
        }
    }

    /**
     * 구매 요청 하나에 대한 승인 생성. 판매처별 주문서는 {@code items[]} 로 전달한다.
     *
     * <p>Core 배포 시점을 분리할 수 있도록 구 단건 계약(평면 필드)도 함께 받는다.
     * {@code items} 가 비어 있고 평면 필드가 오면 판매처 1곳짜리 세션으로 만든다.
     */
    public record CreateAuthorizationSessionRequest(
            @NotBlank @Size(max = 36) String purchaseRequestId,
            @NotBlank @Size(max = 36) String userId,
            @PositiveOrZero Long totalAmountMinor,
            @NotBlank @Pattern(regexp = "KRW") String currency,
            @Valid List<ItemRequest> items,
            @Size(max = 100) String merchantId,
            @Size(max = 150) String checkoutId,
            @Size(max = 100) String checkoutHash,
            @Size(max = 10000) String checkoutJwt,
            @Valid DisplaySnapshotRequest display,
            Long amountMinor) {

        private CreateAuthorizationSessionCommand toCommand() {
            List<CreateAuthorizationSessionCommand.Item> commandItems = items == null
                    || items.isEmpty()
                    ? List.of(legacyItem())
                    : items.stream().map(ItemRequest::toDomain).toList();
            long total = totalAmountMinor != null
                    ? totalAmountMinor
                    : commandItems.stream()
                            .mapToLong(CreateAuthorizationSessionCommand.Item::amountMinor)
                            .sum();
            return new CreateAuthorizationSessionCommand(
                    purchaseRequestId,
                    userId,
                    total,
                    currency,
                    commandItems);
        }

        /** 구 단건 계약. 평면 필드가 하나라도 비면 검증 단계에서 거절된다. */
        private CreateAuthorizationSessionCommand.Item legacyItem() {
            return new CreateAuthorizationSessionCommand.Item(
                    merchantId,
                    checkoutId,
                    checkoutHash,
                    checkoutJwt,
                    display == null ? null : display.toDomain(),
                    amountMinor == null ? 0L : amountMinor,
                    currency);
        }

        @Override
        public String toString() {
            return "CreateAuthorizationSessionRequest["
                    + "purchaseRequestId='" + purchaseRequestId + '\''
                    + ", userId='" + userId + '\''
                    + ", itemCount=" + (items == null ? 0 : items.size())
                    + ", totalAmountMinor=" + totalAmountMinor
                    + ", currency='" + currency + '\''
                    + ", items=<redacted>"
                    + ']';
        }
    }

    public record ItemRequest(
            @NotBlank @Size(max = 100) String merchantId,
            @NotBlank @Size(max = 150) String checkoutId,
            @NotBlank @Size(max = 100) String checkoutHash,
            @NotBlank @Size(max = 10000) String checkoutJwt,
            @NotNull @Valid DisplaySnapshotRequest display,
            @PositiveOrZero long amountMinor,
            @NotBlank @Pattern(regexp = "KRW") String currency) {

        private CreateAuthorizationSessionCommand.Item toDomain() {
            return new CreateAuthorizationSessionCommand.Item(
                    merchantId,
                    checkoutId,
                    checkoutHash,
                    checkoutJwt,
                    display.toDomain(),
                    amountMinor,
                    currency);
        }

        @Override
        public String toString() {
            return "ItemRequest["
                    + "merchantId='" + merchantId + '\''
                    + ", checkoutId='" + checkoutId + '\''
                    + ", checkoutHash=<redacted>"
                    + ", checkoutJwt=<redacted>"
                    + ", display=<redacted>"
                    + ", amountMinor=" + amountMinor
                    + ", currency='" + currency + '\''
                    + ']';
        }
    }

    public record DisplaySnapshotRequest(
            @NotBlank @Size(max = 200) String merchantName,
            @NotEmpty @Size(max = 100) List<@Valid SummaryItemRequest> summary) {

        public DisplaySnapshotRequest {
            summary = summary == null ? List.of() : List.copyOf(summary);
        }

        private AuthorizationDisplaySnapshot toDomain() {
            return new AuthorizationDisplaySnapshot(
                    merchantName,
                    summary.stream()
                            .map(SummaryItemRequest::toDomain)
                            .toList());
        }
    }

    public record SummaryItemRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String option,
            @Min(1) int quantity,
            @PositiveOrZero long amountMinor) {

        private AuthorizationDisplaySnapshot.SummaryItem toDomain() {
            return new AuthorizationDisplaySnapshot.SummaryItem(
                    name,
                    option,
                    quantity,
                    amountMinor);
        }
    }

    public record ApprovalCreatedResponse(
            String approvalId,
            String status,
            String requiredAction,
            Instant expiresAt,
            String nextAction,
            List<CreatedItemResponse> items) {

        public ApprovalCreatedResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }

        private static ApprovalCreatedResponse from(AuthorizationSession session) {
            String nextAction = session.status()
                    == AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                    ? "REGISTER_OR_SELECT_PAYMENT_METHOD"
                    : "APPROVE_IN_APP";
            return new ApprovalCreatedResponse(
                    session.id(),
                    session.status().name(),
                    session.requiredAction(),
                    session.expiresAt(),
                    nextAction,
                    session.items().stream()
                            .map(item -> new CreatedItemResponse(
                                    item.merchantId(),
                                    item.externalCheckoutId()))
                            .toList());
        }
    }

    /**
     * 생성 응답의 에코. Core 가 어느 {@code merchant_checkouts} 행에 승인 ID 를 달지 이 값으로
     * 맞춘다(S15P21A207-486). 순서는 보장하지 않으므로 Core 는 checkoutId 로 매칭한다.
     */
    public record CreatedItemResponse(String merchantId, String checkoutId) {
    }

    public record ApprovalListResponse(List<ApprovalListItemResponse> items) {

        public ApprovalListResponse {
            items = List.copyOf(items);
        }
    }

    /**
     * 인박스 카드. 판매처가 여러 곳이면 대표 판매처명과 개수로 표시한다
     * (예: "A마트 외 2곳"). {@code checkoutId} 는 판매처마다 달라 카드에 담지 않는다.
     */
    public record ApprovalListItemResponse(
            String approvalId,
            String merchantName,
            int merchantCount,
            long totalAmountMinor,
            String currency,
            String status,
            String requiredAction,
            Instant expiresAt) {

        private static ApprovalListItemResponse from(AuthorizationSession session) {
            return new ApprovalListItemResponse(
                    session.id(),
                    session.primaryMerchantName(),
                    session.merchantCount(),
                    session.totalAmountMinor(),
                    session.currency(),
                    session.status().name(),
                    session.requiredAction(),
                    session.expiresAt());
        }
    }

    /**
     * 승인 상세. 판매처별 섹션과 합계를 함께 내려준다. 생체 challenge 는 판매처 수와
     * 관계없이 세션당 1개다.
     */
    public record ApprovalDetailResponse(
            String approvalId,
            List<MerchantResponse> merchants,
            long totalAmountMinor,
            String currency,
            String selectedPaymentMethodId,
            String status,
            String requiredAction,
            Instant expiresAt,
            BiometricChallengeResponse biometricChallenge) {

        private static ApprovalDetailResponse from(
                AuthorizationSession session,
                List<MerchantResponse> merchants,
                BiometricChallengeResponse biometricChallenge) {
            return new ApprovalDetailResponse(
                    session.id(),
                    merchants,
                    session.totalAmountMinor(),
                    session.currency(),
                    session.selectedPaymentMethodId(),
                    session.status().name(),
                    session.requiredAction(),
                    session.expiresAt(),
                    biometricChallenge);
        }
    }

    /**
     * 판매처 한 곳. {@code subtotalAmountMinor} 는 배송비를 포함하므로
     * {@code summary} 의 상품 금액 합보다 클 수 있다.
     */
    public record MerchantResponse(
            String merchantId,
            String merchantName,
            String checkoutId,
            String checkoutHash,
            List<SummaryItemResponse> summary,
            long subtotalAmountMinor) {

        private static MerchantResponse from(
                AuthorizationSessionItem item,
                AuthorizationDisplaySnapshot displaySnapshot) {
            return new MerchantResponse(
                    item.merchantId(),
                    displaySnapshot.merchantName(),
                    item.externalCheckoutId(),
                    CheckoutHashCodec.toExternalHash(item.checkoutDigest()),
                    SummaryItemResponse.from(displaySnapshot),
                    item.amountMinor());
        }
    }

    public record PaymentMethodSelectionRequest(
            @NotBlank @Size(max = 36) String paymentMethodId) {
    }

    public record PaymentMethodSelectionResponse(
            String approvalId,
            String status,
            String selectedPaymentMethodId,
            String requiredAction,
            Instant expiresAt,
            String nextAction) {

        private static PaymentMethodSelectionResponse from(AuthorizationSession session) {
            return new PaymentMethodSelectionResponse(
                    session.id(),
                    session.status().name(),
                    session.selectedPaymentMethodId(),
                    session.requiredAction(),
                    session.expiresAt(),
                    "APPROVE_IN_APP");
        }
    }

    public record SummaryItemResponse(
            String name,
            String option,
            int quantity,
            long amountMinor) {

        private static List<SummaryItemResponse> from(
                AuthorizationDisplaySnapshot displaySnapshot) {
            return displaySnapshot.summary().stream()
                    .map(item -> new SummaryItemResponse(
                            item.name(),
                            item.option(),
                            item.quantity(),
                            item.amountMinor()))
                    .toList();
        }
    }

    public record BiometricChallengeResponse(
            String challengeId,
            String challenge,
            Instant expiresAt) {

        @Override
        public String toString() {
            return "BiometricChallengeResponse["
                    + "challengeId='" + challengeId + '\''
                    + ", challenge=<redacted>"
                    + ", expiresAt=" + expiresAt
                    + ']';
        }
    }

    private static BiometricChallengeResponse toResponse(
            BiometricChallengeService.IssuedChallenge challenge) {
        return new BiometricChallengeResponse(
                challenge.challengeId(),
                challenge.challenge(),
                challenge.expiresAt());
    }

    public record ApproveRequest(
            @Size(max = 36) String paymentMethodId,
            @NotNull @Valid AuthenticationRequest authentication) {

        private AuthorizationApprovalService.ApproveCommand toCommand() {
            return new AuthorizationApprovalService.ApproveCommand(
                    paymentMethodId,
                    authentication.toCommand());
        }

        @Override
        public String toString() {
            return "ApproveRequest["
                    + "paymentMethodId='" + paymentMethodId + '\''
                    + ", authentication=<redacted>"
                    + ']';
        }
    }

    public record AuthenticationRequest(
            @NotBlank @Size(max = 20) String method,
            @Size(max = 36) String challengeId,
            @Size(max = 4096) String assertion,
            @Size(max = 6) String pin) {

        private AuthorizationApprovalService.AuthenticationCommand toCommand() {
            return new AuthorizationApprovalService.AuthenticationCommand(
                    method,
                    challengeId,
                    assertion,
                    pin);
        }

        @Override
        public String toString() {
            return "AuthenticationRequest["
                    + "method='" + method + '\''
                    + ", challengeId='" + challengeId + '\''
                    + ", assertion=<redacted>"
                    + ", pin=<redacted>"
                    + ']';
        }
    }

    public record RejectRequest(@NotBlank @Size(max = 50) String reason) {
    }

    /**
     * credential 은 판매처가 Pay 에 결제를 요청할 때 쓰는 값이라 앱 응답에 싣지 않는다.
     * 판매처별 결제 결과는 승인 상세와 구매 상태 조회에서 확인한다.
     */
    public record ApprovalActionResponse(
            String approvalId,
            String status,
            String requiredAction,
            String authMethod,
            int credentialCount,
            String nextAction) {

        private static ApprovalActionResponse from(
                AuthorizationApprovalService.ApprovalResult result) {
            return new ApprovalActionResponse(
                    result.approvalId(),
                    result.status(),
                    result.requiredAction(),
                    result.authMethod(),
                    result.credentials().size(),
                    result.nextAction());
        }
    }
}
