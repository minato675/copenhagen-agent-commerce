package com.copenhagen.pay.payment.merchant;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import com.copenhagen.pay.payment.credential.mandate.MandateKeySet;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/merchant/v1")
public class PayProfileController {

    private final MandateKeySet keySet;

    public PayProfileController(MandateKeySet keySet) {
        this.keySet = keySet;
    }

    @GetMapping("/profile")
    public ResponseEntity<PayProfileResponse> find(HttpServletRequest httpRequest) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(httpRequest);
        PayRequestContext.requireMerchantPrincipal(httpRequest, principal.subject());
        CacheControl cacheControl = CacheControl
                .maxAge(Duration.ofSeconds(keySet.profileCacheSeconds()))
                .cachePrivate()
                .mustRevalidate();
        return ResponseEntity.ok()
                .cacheControl(cacheControl)
                .body(PayProfileResponse.from(keySet));
    }
}
