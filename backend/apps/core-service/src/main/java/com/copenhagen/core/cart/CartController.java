package com.copenhagen.core.cart;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * API 명세서 v5의 CART 4건을 REST로 노출한다. 명세의 Method는 {@code MCP}(tool)지만, 순서 24(상품 조회)와
 * 같은 방침으로 Core는 REST까지만 만들고 mcp-server tool 등록·어댑터는 후속 이슈로 둔다.
 *
 * <p>삭제만 {@code 204}가 아니라 {@code 200 + 바디}다 — 명세가 "성공 시 새 cartVersion 반환"을 요구해
 * 본문이 필요하고, {@code DELETE} 요청 본문을 피하려고 버전을 쿼리 파라미터로 받는다.
 */
@RestController
@RequestMapping("/api/core/v1/cart")
public class CartController {

    private final CartService cartService;
    private final UserRepository userRepository;

    public CartController(CartService cartService, UserRepository userRepository) {
        this.cartService = cartService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public CartView get(HttpServletRequest request) {
        return cartService.get(requireUserId(request));
    }

    @PostMapping("/items")
    public ResponseEntity<CartAddResult> add(
            HttpServletRequest request, @Valid @RequestBody AddItemRequest body) {
        long userId = requireUserId(request);
        CartAddResult result = cartService.add(
                userId,
                body.cartVersion(),
                body.merchantId(),
                body.productId(),
                body.optionValueIdsOrEmpty(),
                body.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/items/{cartItemId}")
    public CartVersionResponse updateQuantity(
            HttpServletRequest request,
            @PathVariable UUID cartItemId,
            @Valid @RequestBody UpdateItemRequest body) {
        long userId = requireUserId(request);
        return new CartVersionResponse(
                cartService.updateQuantity(userId, cartItemId, body.cartVersion(), body.quantity()));
    }

    @DeleteMapping("/items/{cartItemId}")
    public CartVersionResponse remove(
            HttpServletRequest request,
            @PathVariable UUID cartItemId,
            @RequestParam int cartVersion) {
        long userId = requireUserId(request);
        return new CartVersionResponse(cartService.remove(userId, cartItemId, cartVersion));
    }

    /** 장바구니는 {@code carts.user_id}(FK)로 저장되므로 principal(publicId)을 내부 id로 변환한다. */
    private long requireUserId(HttpServletRequest request) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return userRepository.findByPublicId(principal)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID))
                .id();
    }

    /** {@code optionValueIds}만 명세에서 선택 파라미터다. 나머지는 전부 필수다. */
    public record AddItemRequest(
            @NotNull
            @Min(0)
            Integer cartVersion,

            @NotBlank
            String productId,

            @NotBlank
            String merchantId,

            List<String> optionValueIds,

            @NotNull
            @Min(1)
            Integer quantity) {

        List<String> optionValueIdsOrEmpty() {
            return optionValueIds == null ? List.of() : optionValueIds;
        }
    }

    public record UpdateItemRequest(
            @NotNull
            @Min(0)
            Integer cartVersion,

            @NotNull
            @Min(1)
            Integer quantity) {
    }

    public record CartVersionResponse(int cartVersion) {
    }
}
