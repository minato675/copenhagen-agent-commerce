package com.copenhagen.core.internalapi.cart;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.MediaType;
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

import com.copenhagen.core.internalapi.auth.InternalRequestContext;

/**
 * mcp-server의 {@code get_cart}·{@code add_cart_item}·{@code update_cart_item}·
 * {@code delete_cart_item}이 부르는 Core 엔드포인트(순서 34).
 *
 * <p>변경 3종은 이미 직렬화된 JSON 문자열을 그대로 돌려준다 — 같은 멱등키로 재전송하면 저장해 둔 최초
 * 응답을 그대로 재생해야 해서다(설계서 §6). 삭제도 {@code 204}가 아니라 {@code 200}에 전체 장바구니를
 * 싣는다.
 */
@RestController
@RequestMapping("/internal/v1/carts/current")
public class InternalCartController {

    private final InternalCartService cartService;

    public InternalCartController(InternalCartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public InternalCartResponse get(HttpServletRequest request) {
        return cartService.get(InternalRequestContext.require(request));
    }

    @PostMapping("/items")
    public ResponseEntity<String> add(
            HttpServletRequest request, @Valid @RequestBody AddItemRequest body) {
        return json(cartService.add(
                InternalRequestContext.require(request),
                request.getRequestURI(),
                body.cartVersion(),
                body.merchantId(),
                body.merchantProductId(),
                body.merchantVariantId(),
                body.quantity()));
    }

    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<String> update(
            HttpServletRequest request,
            @PathVariable UUID cartItemId,
            @Valid @RequestBody UpdateItemRequest body) {
        return json(cartService.update(
                InternalRequestContext.require(request),
                request.getRequestURI(),
                cartItemId,
                body.cartVersion(),
                body.merchantVariantId(),
                body.quantity()));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<String> remove(
            HttpServletRequest request,
            @PathVariable UUID cartItemId,
            @RequestParam int cartVersion) {
        return json(cartService.delete(
                InternalRequestContext.require(request), request.getRequestURI(), cartItemId, cartVersion));
    }

    private ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    public record AddItemRequest(
            @NotNull
            @Min(0)
            Integer cartVersion,

            @NotBlank
            String merchantId,

            @NotBlank
            String merchantProductId,

            @NotBlank
            String merchantVariantId,

            @NotNull
            @Min(1)
            @Max(InternalCartService.MAX_QUANTITY_PER_ITEM)
            Integer quantity) {
    }

    public record UpdateItemRequest(
            @NotNull
            @Min(0)
            Integer cartVersion,

            @NotBlank
            String merchantVariantId,

            @NotNull
            @Min(1)
            @Max(InternalCartService.MAX_QUANTITY_PER_ITEM)
            Integer quantity) {
    }
}
