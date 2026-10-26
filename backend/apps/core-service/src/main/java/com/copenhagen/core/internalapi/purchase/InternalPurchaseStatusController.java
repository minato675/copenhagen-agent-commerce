package com.copenhagen.core.internalapi.purchase;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.purchase.PurchaseWorkflowService;
import com.copenhagen.core.purchase.PurchaseWorkflowService.View;

@RestController
@RequestMapping("/internal/v1/purchase-requests")
public class InternalPurchaseStatusController {
    private final PurchaseWorkflowService service;

    public InternalPurchaseStatusController(PurchaseWorkflowService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public View status(@PathVariable UUID id, HttpServletRequest request) {
        InternalRequestContext context = InternalRequestContext.require(request);
        return service.view(id, context.userId(), context.connectionId());
    }

    @PostMapping("/{id}/notifications")
    public View resend(@PathVariable UUID id, HttpServletRequest request) {
        InternalRequestContext context = InternalRequestContext.require(request);
        return service.resend(id, context.userId(), context.connectionId(), context.requireIdempotencyKey());
    }
}
