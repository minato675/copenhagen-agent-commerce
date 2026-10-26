package com.copenhagen.core.internalapi.purchase;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.internalapi.auth.InternalRequestContext;

/**
 * mcp-server의 {@code prepare_purchase_handoff}가 부르는 Core 엔드포인트(순서 34).
 * 조회가 아니라 인계 레코드를 만드는 호출이지만 {@code 200}이다 — mcp-server가 2xx 중 {@code 201}을
 * 따로 다루지 않고, 같은 멱등키 재전송이 최초 응답을 그대로 재생해야 하기 때문이다.
 */
@RestController
@RequestMapping("/internal/v1/purchase-requests")
public class InternalPurchaseController {

    private final InternalPurchaseService purchaseService;

    public InternalPurchaseController(InternalPurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @PostMapping
    public ResponseEntity<String> create(
            HttpServletRequest request, @Valid @RequestBody HandoffRequest body) {
        String response = purchaseService.create(
                InternalRequestContext.require(request),
                request.getRequestURI(),
                body.cartId(),
                body.cartVersion());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    public record HandoffRequest(
            @NotNull
            UUID cartId,

            @NotNull
            @Min(0)
            Integer cartVersion) {
    }
}
