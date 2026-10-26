package com.copenhagen.core.internalapi.catalog;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.copenhagen.core.internalapi.config.InternalApiProperties;
import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;
import com.copenhagen.core.product.CatalogProductView;
import com.copenhagen.core.product.CatalogSearchItem;
import com.copenhagen.core.product.CatalogSearchResult;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.product.ProductService;

/**
 * 내부 계약(설계서 §4.1·§4.2)에 맞춘 Catalog 조회. 데이터 원본은 앱 경로와 같은
 * {@link ProductService}이고, 여기서 하는 일은 비교 묶음·커서·플레이스홀더 채우기다.
 *
 * <p>Merchant Catalog에 브랜드·카테고리·이미지·재고 수량·배송비가 없다. 없는 값은 {@code null}이나
 * 고정 플레이스홀더로 두고 0으로 만들지 않는다(ADR-0004, {@code core-integration.md}).
 */
@Service
public class InternalCatalogService {

    /** Catalog에 카테고리 개념이 없어 고정 카테고리 하나로 답한다. */
    static final String CATEGORY_ALL_ID = "all";
    static final String CATEGORY_ALL_NAME = "전체";

    static final int MAX_PAGE_SIZE = 30;
    /** mcp-server 입력 스키마의 수량 상한과 같은 값이다(D-G). */
    static final int MAX_QUANTITY_PER_ITEM = 99;

    private static final String CURRENCY = "KRW";
    private static final String SORT_PRICE_ASC = "PRICE_ASC";
    private static final String SORT_PRICE_DESC = "PRICE_DESC";
    private static final String AVAILABILITY_UNKNOWN = "UNKNOWN";
    private static final Set<String> AVAILABILITY_VALUES =
            Set.of("IN_STOCK", "OUT_OF_STOCK", "UNKNOWN", "DISCONTINUED");

    private final ProductService productService;
    private final MerchantsProperties merchantsProperties;
    private final InternalApiProperties properties;
    private final Clock clock;

    public InternalCatalogService(
            ProductService productService,
            MerchantsProperties merchantsProperties,
            InternalApiProperties properties,
            Clock coreClock) {
        this.productService = productService;
        this.merchantsProperties = merchantsProperties;
        this.properties = properties;
        this.clock = coreClock;
    }

    public InternalSearchResponse search(InternalSearchQuery query) {
        int pageSize = pageSize(query.pageSize());
        int offset = SearchCursor.decode(query.cursor());

        // 카테고리·브랜드는 Catalog가 주지 않는 정보다. 'all' 외의 카테고리나 브랜드를 지정하면
        // 맞출 수 있는 항목이 없으므로 빈 결과로 답한다(앱 경로의 현행 동작과 같다).
        CatalogSearchResult result = excludesEverything(query)
                ? new CatalogSearchResult(List.of(), List.of())
                : productService.searchForInternal(
                        query.query(), query.merchantId(), query.minPrice(), query.maxPrice());

        List<InternalSearchResponse.Product> products = group(result.items());
        sort(products, query.sort());

        int toIndex = Math.min(offset + pageSize, products.size());
        List<InternalSearchResponse.Product> page = offset >= products.size()
                ? List.of()
                : List.copyOf(products.subList(offset, toIndex));
        String nextCursor = toIndex < products.size() ? SearchCursor.encode(toIndex) : null;

        return new InternalSearchResponse(
                page,
                new InternalSearchResponse.Filters(
                        List.of(new InternalNamedId(CATEGORY_ALL_ID, CATEGORY_ALL_NAME)), merchants()),
                result.failedMerchants(),
                nextCursor,
                clock.instant().toString());
    }

    public InternalDetailResponse detail(String merchantId, String merchantProductId) {
        CatalogProductView product = productService.detailForInternal(merchantId, merchantProductId);

        List<InternalDetailResponse.OptionGroup> optionGroups = new ArrayList<>();
        List<InternalDetailResponse.Variant> variants = new ArrayList<>();
        for (CatalogProductView.OptionGroup group : product.optionGroups()) {
            List<InternalNamedId> values = new ArrayList<>();
            for (CatalogProductView.OptionValue value : group.values()) {
                values.add(new InternalNamedId(value.optionValueId(), value.label()));
                // 새 Catalog는 옵션 단가·재고를 제공한다. 구형 제공자는 상품 노출가로 호환한다.
                variants.add(new InternalDetailResponse.Variant(
                        value.optionValueId(),
                        value.priceAmount() == null ? product.priceAmount() : value.priceAmount(),
                        product.currency(),
                        availability(product.availability()),
                        value.stockQuantity() == null ? null : value.stockQuantity().longValue(),
                        value.stockQuantity() == null || value.stockQuantity() > 0,
                        1,
                        value.stockQuantity() == null ? MAX_QUANTITY_PER_ITEM
                                : Math.max(1, Math.min(MAX_QUANTITY_PER_ITEM, value.stockQuantity())),
                        List.of(value.optionValueId())));
            }
            optionGroups.add(new InternalDetailResponse.OptionGroup(
                    ComparisonGroups.optionGroupId(group.name()), group.name(), List.copyOf(values)));
        }

        return new InternalDetailResponse(
                product.merchantId(),
                product.merchantProductId(),
                product.name(),
                product.imageUrl(),
                List.copyOf(optionGroups),
                List.copyOf(variants),
                new InternalDetailResponse.Shipping(null, AVAILABILITY_UNKNOWN),
                clock.instant().toString());
    }

    /** 같은 이름을 파는 여러 merchant의 offer를 한 묶음으로 합친다. 묶음에 offer가 없을 수는 없다. */
    private List<InternalSearchResponse.Product> group(List<CatalogSearchItem> items) {
        Map<String, List<CatalogSearchItem>> grouped = new LinkedHashMap<>();
        for (CatalogSearchItem item : items) {
            grouped.computeIfAbsent(ComparisonGroups.normalize(item.name()), key -> new ArrayList<>())
                    .add(item);
        }
        List<InternalSearchResponse.Product> products = new ArrayList<>(grouped.size());
        for (List<CatalogSearchItem> group : grouped.values()) {
            List<InternalSearchResponse.Offer> offers = group.stream()
                    .map(item -> new InternalSearchResponse.Offer(
                            item.merchantId(),
                            item.merchantName(),
                            item.merchantProductId(),
                            item.priceAmount(),
                            CURRENCY,
                            availability(item.availability())))
                    .toList();
            products.add(new InternalSearchResponse.Product(
                    ComparisonGroups.comparisonGroupId(group.get(0).name()),
                    group.get(0).name(),
                    null,
                    group.stream().map(CatalogSearchItem::imageUrl)
                            .filter(image -> image != null && !image.isBlank()).findFirst().orElse(null),
                    new InternalNamedId(CATEGORY_ALL_ID, CATEGORY_ALL_NAME),
                    offers));
        }
        return products;
    }

    /** 묶음의 대표 가격은 최저가 offer다. {@code RELEVANCE}는 merchant 조회 순서를 그대로 둔다. */
    private void sort(List<InternalSearchResponse.Product> products, String sort) {
        if (!SORT_PRICE_ASC.equals(sort) && !SORT_PRICE_DESC.equals(sort)) {
            return;
        }
        Comparator<InternalSearchResponse.Product> byLowestPrice =
                Comparator.comparingLong(InternalCatalogService::lowestPrice);
        products.sort(SORT_PRICE_ASC.equals(sort) ? byLowestPrice : byLowestPrice.reversed());
    }

    private static long lowestPrice(InternalSearchResponse.Product product) {
        return product.offers().stream()
                .map(InternalSearchResponse.Offer::priceAmount)
                .filter(price -> price != null)
                .mapToLong(Long::longValue)
                .min()
                .orElse(Long.MAX_VALUE);
    }

    private boolean excludesEverything(InternalSearchQuery query) {
        boolean otherCategory = query.categoryId() != null && !query.categoryId().isBlank()
                && !CATEGORY_ALL_ID.equals(query.categoryId());
        boolean brandGiven = query.brand() != null && !query.brand().isBlank();
        return otherCategory || brandGiven;
    }

    private int pageSize(Integer requested) {
        if (requested == null) {
            return properties.defaultPageSize();
        }
        if (requested < 1 || requested > MAX_PAGE_SIZE) {
            throw new InternalApiException(InternalErrorCode.INVALID_ARGUMENT, "pageSize가 올바르지 않습니다.");
        }
        return requested;
    }

    /** merchant가 모르는 값을 보내도 계약을 깨지 않도록 허용 집합 밖은 UNKNOWN으로 낮춘다. */
    private String availability(String value) {
        return value != null && AVAILABILITY_VALUES.contains(value) ? value : AVAILABILITY_UNKNOWN;
    }

    private List<InternalNamedId> merchants() {
        List<MerchantsProperties.MerchantConfig> merchants = merchantsProperties.merchants();
        if (merchants == null) {
            return List.of();
        }
        return merchants.stream()
                .map(merchant -> new InternalNamedId(merchant.id(), merchant.displayName()))
                .toList();
    }
}
