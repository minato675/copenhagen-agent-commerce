package com.copenhagen.core.purchase;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.purchase.PurchaseWorkflowService.View;

@RestController
@RequestMapping("/api/core/v1/purchase-requests")
public class PurchaseWorkflowController {
    private final PurchaseWorkflowService service;
    private final UserRepository users;

    public PurchaseWorkflowController(PurchaseWorkflowService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<View> recent(HttpServletRequest request) {
        return service.recent(userId(request));
    }

    @GetMapping("/{id}")
    public View get(@PathVariable UUID id, HttpServletRequest request) {
        return service.view(id, userId(request), null);
    }

    @PostMapping("/{id}/resume")
    public View resume(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody ResumeRequest body, HttpServletRequest request) {
        return service.resume(id, userId(request), body.addressId(), body.cartVersion(), key);
    }

    @PostMapping("/{id}/notifications")
    public View resend(@PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID key,
            HttpServletRequest request) {
        return service.resend(id, userId(request), null, key);
    }

    private long userId(HttpServletRequest request) {
        return users.findByPublicId(CoreRequestContext.requirePrincipal(request))
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID)).id();
    }

    public record ResumeRequest(@NotNull UUID addressId, @NotNull @Min(0) Integer cartVersion) {
    }
}
