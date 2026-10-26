package com.copenhagen.pay.payment.notification;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.contract.internalapi.PurchaseNotificationStatus;

@RestController
@RequestMapping("/internal/v1/purchase-notifications")
public class PurchaseNotificationController {
    private final PurchaseNotificationService service;

    public PurchaseNotificationController(PurchaseNotificationService service) {
        this.service = service;
    }

    @PostMapping
    public void create(@Valid @RequestBody HandoffNotification body) {
        service.create(body.userId(), body.purchaseRequestId());
    }

    @GetMapping("/{purchaseId}")
    public List<PurchaseNotificationStatus> status(@PathVariable UUID purchaseId, @RequestParam UUID userId) {
        return service.status(userId, purchaseId);
    }

    @PostMapping("/{purchaseId}/resend")
    public void resend(@PathVariable UUID purchaseId, @RequestParam UUID userId,
            @RequestHeader("Idempotency-Key") UUID key) {
        service.resend(userId, purchaseId, key);
    }

    public record HandoffNotification(@NotNull UUID userId, @NotNull UUID purchaseRequestId) {
    }
}
