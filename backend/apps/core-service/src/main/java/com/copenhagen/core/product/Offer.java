package com.copenhagen.core.product;

public record Offer(String merchantId, String merchantName, Long price, String currency, String availability) {
}
