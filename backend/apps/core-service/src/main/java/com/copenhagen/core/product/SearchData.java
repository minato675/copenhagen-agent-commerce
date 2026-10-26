package com.copenhagen.core.product;

import java.util.List;

public record SearchData(
        String kind, String mode, String status, SearchQuery query, List<CatalogProduct> products,
        List<String> categories, List<MerchantRef> merchants, List<String> failedMerchants,
        int total, boolean hasNext, String checkedAt) {
}
