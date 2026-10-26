package com.copenhagen.core.product;

/** {@code product}는 NOT_FOUND일 때 값이 {@code null}이어도 키 자체는 항상 직렬화한다(스키마가 필수 키로 요구). */
public record DetailData(
        String kind, String mode, String status, DetailRequestEcho request,
        ProductDetail product, String checkedAt) {
}
