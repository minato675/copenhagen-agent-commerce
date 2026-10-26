package com.copenhagen.core.internalapi.history;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.internalapi.auth.InternalRequestContext;

/**
 * mcp-server {@code get_purchase_history}가 부르는 Core 엔드포인트(순서 43, 이슈 S15P21A207-490).
 *
 * <p>조회라 {@code Idempotency-Key}를 요구하지 않는다. 필요한 scope는 기존 {@code purchase:handoff}로,
 * {@code InternalScopes}가 경로를 보고 정한다(D-A).
 */
@RestController
@RequestMapping("/internal/v1/purchase-history")
public class InternalPurchaseHistoryController {

    private final InternalPurchaseHistoryService purchaseHistoryService;

    public InternalPurchaseHistoryController(InternalPurchaseHistoryService purchaseHistoryService) {
        this.purchaseHistoryService = purchaseHistoryService;
    }

    @GetMapping
    public InternalPurchaseHistoryResponse get(
            HttpServletRequest request,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String cursor) {
        return purchaseHistoryService.get(InternalRequestContext.require(request), pageSize, cursor);
    }
}
