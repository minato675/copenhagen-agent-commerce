package com.copenhagen.core.orderhistory;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.orderhistory.OrderHistoryService.OrderV2PageResponse;
import com.copenhagen.core.orderhistory.OrderHistoryService.OrderV2Response;

/**
 * 주문내역 v2. 요청 형태와 페이지네이션은 v1과 같고 항목에 상품 식별자
 * ({@code merchantId}·{@code merchantProductId}·{@code merchantVariantId})와 {@code imageUrl}이 붙는다.
 *
 * <p>v1({@link OrderHistoryController})은 그대로 둔다 — 이미 배포된 앱이 쓰고 있고, 필드가 늘어난 것 외에
 * 달라진 계약이 없어 굳이 바꿀 이유가 없다.
 */
@RestController
@RequestMapping("/api/core/v2/orders")
public class OrderHistoryV2Controller {

    private final OrderHistoryService service;
    private final UserRepository userRepository;

    public OrderHistoryV2Controller(OrderHistoryService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public OrderV2PageResponse list(
            HttpServletRequest request,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String status) {
        return service.listV2(requireUserId(request), size, cursor, status);
    }

    @GetMapping("/{orderId}")
    public OrderV2Response get(HttpServletRequest request, @PathVariable UUID orderId) {
        return service.getV2(requireUserId(request), orderId);
    }

    private long requireUserId(HttpServletRequest request) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return userRepository.findByPublicId(principal)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID))
                .id();
    }
}
