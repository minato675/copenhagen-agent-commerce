package com.copenhagen.core.purchase;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.copenhagen.contract.internalapi.PurchaseNotificationStatus;
import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.order.OrderValidationService;
import com.copenhagen.core.order.PurchaseRequest;
import com.copenhagen.core.payment.PaymentApprovalClient;
import com.copenhagen.core.purchase.PurchaseWorkflowRepository.Row;

@Service
public class PurchaseWorkflowService {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "PARTIALLY_COMPLETED",
            "CANCELLED", "EXPIRED", "REJECTED", "FAILED");
    private final PurchaseWorkflowRepository repository;
    private final OrderValidationService validation;
    private final PaymentApprovalClient pay;
    private final CartMerchants merchants;
    private final Clock clock;

    public PurchaseWorkflowService(PurchaseWorkflowRepository repository, OrderValidationService validation,
            PaymentApprovalClient pay, CartMerchants merchants, Clock clock) {
        this.repository = repository;
        this.validation = validation;
        this.pay = pay;
        this.merchants = merchants;
        this.clock = clock;
    }

    public View view(UUID id, long userId, Long connectionId) {
        return view(id, userId, connectionId, true);
    }

    private View view(UUID id, long userId, Long connectionId, boolean includeNotifications) {
        Row row = repository.require(id, userId);
        if (connectionId != null && row.connectionId() != connectionId) {
            throw new CoreException(CoreErrorCode.ORDER_NOT_FOUND);
        }
        List<CheckoutView> checkouts = repository.checkouts(row.id()).stream().map(c -> new CheckoutView(
                merchants.merchantId(c.merchantId()), c.approvalId(),
                "CANCELLED".equals(c.status()) && "USER_REJECTED".equals(c.failureCode()) ? "REJECTED" : c.status(),
                c.totalAmount(), c.currency())).toList();
        String status = aggregate(row.status(), checkouts);
        if (checkouts.isEmpty() && !TERMINAL.contains(status) && !clock.instant().isBefore(row.expiresAt())) {
            status = "EXPIRED";
        }
        List<PurchaseNotificationStatus> notifications;
        try {
            notifications = includeNotifications
                    ? pay.notificationStatus(row.userPublicId(), row.publicId()) : List.of();
        } catch (RuntimeException exception) {
            notifications = List.of(new PurchaseNotificationStatus(null, "UNAVAILABLE", "PAY_UNAVAILABLE"));
        }
        return new View(row.publicId(), status, row.cartPublicId(), row.cartVersion(), row.expiresAt(),
                row.failureCode(), nextAction(status), checkouts, notifications);
    }

    public List<View> recent(long userId) {
        return repository.recent(userId).stream().map(row -> view(row.publicId(), userId, null, false)).toList();
    }

    public View resume(UUID id, long userId, UUID addressId, int version, UUID key) {
        repository.resume(id, userId, addressId, version, key, clock.instant());
        return view(id, userId, null);
    }

    public View resend(UUID id, long userId, Long connectionId, UUID key) {
        View current = view(id, userId, connectionId);
        if (!TERMINAL.contains(current.status())) {
            Row row = repository.require(id, userId);
            if ("PENDING_ADDRESS".equals(current.status())) {
                pay.notifyPurchase(row.userPublicId(), id);
            }
            pay.resendNotification(row.userPublicId(), id, key);
        }
        return view(id, userId, connectionId);
    }

    public void process(Row candidate) {
        if (!repository.claim(candidate.id(), clock.instant())) {
            return;
        }
        Row row = repository.require(candidate.publicId(), candidate.userId());
        try {
            if (repository.hasCheckouts(row.id())) {
                repository.finish(row.id(), "AWAITING_APPROVAL", null, clock.instant());
                return;
            }
            if (!clock.instant().isBefore(row.expiresAt())) {
                repository.finish(row.id(), "EXPIRED", null, clock.instant());
                return;
            }
            // 배송지는 사용자가 앱에서 직접 고른다(PAY-05.1). 기본 배송지를 서버가 대신 선택하지 않는다.
            // shipping_address_id를 채우는 경로는 resume 하나뿐이다.
            if (row.addressId() == null) {
                pay.notifyPurchase(row.userPublicId(), row.publicId());
                repository.notificationError(row.id(), null);
                repository.finish(row.id(), "PENDING_ADDRESS", null, clock.instant());
                return;
            }
            validation.validateExisting(new PurchaseRequest(row.id(), row.publicId(), row.userId(),
                    row.cartId(), row.status()), row.cartPublicId(), row.cartVersion(), row.addressId());
            repository.finish(row.id(), "AWAITING_APPROVAL", null, clock.instant());
        } catch (CoreException exception) {
            CoreErrorCode code = exception.errorCode();
            if (code == CoreErrorCode.ADDRESS_NOT_FOUND) {
                repository.requireNewAddress(row.id());
                return;
            }
            boolean retry = code == CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE
                    || code == CoreErrorCode.MERCHANT_PROFILE_UNAVAILABLE
                    || code == CoreErrorCode.PAYMENT_APPROVAL_UNAVAILABLE;
            repository.finish(row.id(), retry ? row.status() : "FAILED", code.code(), clock.instant());
        } catch (RuntimeException exception) {
            repository.notificationError(row.id(), "SERVICE_UNAVAILABLE");
            repository.finish(row.id(), row.status(), "SERVICE_UNAVAILABLE", clock.instant());
        }
    }

    static String aggregate(String original, List<CheckoutView> rows) {
        if (rows.isEmpty()) {
            return original;
        }
        if (rows.stream().allMatch(r -> "COMPLETED".equals(r.status()))) {
            return "COMPLETED";
        }
        if (rows.stream().anyMatch(r -> "COMPLETING".equals(r.status()))) {
            return "COMPLETING";
        }
        if (rows.stream().anyMatch(r -> "CREATING".equals(r.status()))) {
            return "CREATING_CHECKOUTS";
        }
        if (rows.stream().anyMatch(r -> "AWAITING_APPROVAL".equals(r.status()))) {
            return "AWAITING_APPROVAL";
        }
        if (rows.stream().anyMatch(r -> "COMPLETED".equals(r.status()))) {
            return "PARTIALLY_COMPLETED";
        }
        if (rows.stream().anyMatch(r -> "FAILED".equals(r.status()))) {
            return "FAILED";
        }
        return rows.stream().anyMatch(r -> "REJECTED".equals(r.status())) ? "REJECTED" : "EXPIRED";
    }

    private static String nextAction(String status) {
        return switch (status) {
            case "PENDING_ADDRESS" -> "SELECT_SHIPPING_ADDRESS";
            case "AWAITING_APPROVAL" -> "OPEN_APPROVAL";
            case "CREATING_CHECKOUTS", "COMPLETING" -> "WAIT";
            default -> "SHOW_RESULT";
        };
    }

    public record View(UUID purchaseRequestId, String status, UUID cartId, int cartVersion,
            java.time.Instant expiresAt, String failureCode, String nextAction,
            List<CheckoutView> merchantCheckouts, List<PurchaseNotificationStatus> notifications) {
    }

    public record CheckoutView(String merchantId, String approvalId, String status, long totalAmount, String currency) {
    }
}
