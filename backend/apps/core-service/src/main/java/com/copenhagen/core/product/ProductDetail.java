package com.copenhagen.core.product;

import java.util.List;

public record ProductDetail(
        String productId, String name, String brand, String category, String imageUrl, String description,
        List<ProductSpec> specs, Offer offer, List<Variant> variants,
        boolean optionsComplete, Boolean optionsRequired, String selectionStatus, String selectedOptionId,
        Long stockQuantity, Shipping shipping) {
}
