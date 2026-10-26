package com.copenhagen.core.cart;

import java.util.UUID;

/**
 * 사용자당 ACTIVE 장바구니는 1건이다({@code uq_carts_user_active}). {@code version}은 낙관적 잠금용이고
 * 담기·수량 변경·삭제가 성공할 때마다 1씩 오른다.
 */
public record Cart(long id, UUID publicId, long userId, int version) {
}
