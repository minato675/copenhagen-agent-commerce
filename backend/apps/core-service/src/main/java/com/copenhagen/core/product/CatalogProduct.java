package com.copenhagen.core.product;

import java.util.List;

public record CatalogProduct(
        String productId, String name, String brand, String category, String imageUrl, List<Offer> offers) {
}
