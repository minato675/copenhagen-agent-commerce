package com.copenhagen.core.order;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * API 명세서 v5의 `주문 검증`. 조회가 아니라 구매 요청과 merchant 주문서를 만드는 호출이라 {@code 201}이다.
 *
 * <p>실패는 {@code 409}에 대표 오류코드와 항목별 사유({@code issues})를 함께 싣는다. 검증이 반려돼도
 * 장바구니는 건드리지 않는다(명세 비고, ORDER-03).
 */
@RestController
@RequestMapping("/api/core/v1/orders")
public class OrderValidationController {

    private final OrderValidationService orderValidationService;
    private final UserRepository userRepository;

    public OrderValidationController(
            OrderValidationService orderValidationService, UserRepository userRepository) {
        this.orderValidationService = orderValidationService;
        this.userRepository = userRepository;
    }

    @PostMapping("/validations")
    public ResponseEntity<OrderValidationResult> validate(
            HttpServletRequest request, @Valid @RequestBody ValidateRequest body) {
        OrderValidationResult result = orderValidationService.validate(
                requireUserId(request), body.cartId(), body.cartVersion(), body.addressId());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** 구매 요청은 {@code purchase_requests.user_id}(FK)로 저장되므로 principal(publicId)을 내부 id로 바꾼다. */
    private long requireUserId(HttpServletRequest request) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return userRepository.findByPublicId(principal)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID))
                .id();
    }

    public record ValidateRequest(
            @NotNull
            UUID cartId,

            @NotNull
            @Min(0)
            Integer cartVersion,

            @NotNull
            UUID addressId) {
    }
}
