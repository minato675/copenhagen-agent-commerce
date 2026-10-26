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
import com.copenhagen.core.orderhistory.OrderHistoryService.OrderPageResponse;
import com.copenhagen.core.orderhistory.OrderHistoryService.OrderResponse;

@RestController
@RequestMapping("/api/core/v1/orders")
public class OrderHistoryController {

    private final OrderHistoryService service;
    private final UserRepository userRepository;

    public OrderHistoryController(OrderHistoryService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public OrderPageResponse list(
            HttpServletRequest request,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String status) {
        return service.list(requireUserId(request), size, cursor, status);
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(HttpServletRequest request, @PathVariable UUID orderId) {
        return service.get(requireUserId(request), orderId);
    }

    private long requireUserId(HttpServletRequest request) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return userRepository.findByPublicId(principal)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID))
                .id();
    }
}
