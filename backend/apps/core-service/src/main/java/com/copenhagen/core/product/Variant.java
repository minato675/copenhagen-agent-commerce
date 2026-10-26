package com.copenhagen.core.product;

public record Variant(String optionId, String label, Long price, String availability, Boolean purchaseAllowed) {
}
