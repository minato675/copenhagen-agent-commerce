package com.copenhagen.core.cart;

import java.util.UUID;

/** {@code add_cart_item} 응답 — 명세의 "성공 시 cartItemId, 새 cartVersion 반환". */
public record CartAddResult(UUID cartItemId, int cartVersion) {
}
