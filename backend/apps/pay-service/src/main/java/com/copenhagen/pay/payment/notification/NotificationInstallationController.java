package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pay/v1/notification-installations")
public class NotificationInstallationController {

    private final NotificationInstallationService service;

    public NotificationInstallationController(NotificationInstallationService service) {
        this.service = service;
    }

    @PutMapping
    public ResponseEntity<Void> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {
        service.register(userId(httpRequest), request.fid());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{fid}")
    public ResponseEntity<Void> unregister(
            @PathVariable String fid,
            HttpServletRequest httpRequest) {
        service.unregister(userId(httpRequest), fid);
        return ResponseEntity.noContent().build();
    }

    private String userId(HttpServletRequest request) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(request);
        return principal.subject();
    }

    public record RegisterRequest(@NotBlank @Size(max = 255) String fid) {

        @Override
        public String toString() {
            return "RegisterRequest[fid=<redacted>]";
        }
    }
}
