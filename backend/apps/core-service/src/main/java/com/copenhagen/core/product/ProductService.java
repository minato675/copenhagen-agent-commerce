package com.copenhagen.core.product;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.copenhagen.contract.ucp.CatalogOptionValue;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

@Service
public class ProductService {

    private static final String DEFAULT_CONNECTION_REF = "default";
    private static final String KRW = "KRW";

    private final Map<String, MerchantCatalogClient> clients;
    private final Clock clock;
    private final CatalogSearchFanout catalogSearchFanout;

    public ProductService(Map<String, MerchantCatalogClient> merchantCatalogClients, Clock coreClock,
            CatalogSearchFanout catalogSearchFanout) {
        this.clients = merchantCatalogClients;
        this.clock = coreClock;
        this.catalogSearchFanout = catalogSearchFanout;
    }

    public SearchEnvelope search(UUID principal, SearchQuery query) {
        List<MerchantCatalogClient> targets = targets(query.merchantId());

        List<CatalogProduct> products = new ArrayList<>();
        List<String> failedMerchants = new ArrayList<>();
        for (MerchantCatalogClient client : targets) {
            try {
                for (MerchantCatalogClient.CatalogItem item
                        : client.search(query.query(), query.maxPrice(), KRW)) {
                    if (!KRW.equals(item.price().currency())) {
                        continue;
                    }
                    if (query.minPrice() != null && item.price().amount() < query.minPrice()) {
                        continue;
                    }
                    if (!query.category().isBlank() || !query.brand().isBlank()) {
                        continue;
                    }
                    products.add(new CatalogProduct(
                            item.productId(), item.title(), "", "", item.imageUrl() == null ? "" : item.imageUrl(),
                            List.of(new Offer(client.merchantId(), client.merchantName(),
                                    item.price().amount(), KRW, item.availability()))));
                }
            } catch (CoreException exception) {
                failedMerchants.add(client.merchantId());
            }
        }

        sort(products, query.sort());
        int total = products.size();
        List<CatalogProduct> page = paginate(products, query.page(), query.pageSize());
        boolean hasNext = (long) query.page() * query.pageSize() < total;
        String status = !failedMerchants.isEmpty() ? "PARTIAL" : page.isEmpty() ? "EMPTY" : "OK";

        SearchData data = new SearchData(
                "catalog.search", "LIVE", status, query, page,
                List.of(), merchantRefs(targets), failedMerchants, total, hasNext,
                clock.instant().toString());
        return new SearchEnvelope(principal.toString(), DEFAULT_CONNECTION_REF, data);
    }

    public DetailEnvelope getDetail(UUID principal, String productId, String merchantId, String optionId) {
        MerchantCatalogClient client = clients.get(merchantId);
        if (client == null) {
            throw new CoreException(
                    CoreErrorCode.INVALID_REQUEST, "등록되지 않은 merchantId입니다.", null, "CHECK_REQUEST");
        }

        MerchantCatalogClient.CatalogProductDetail detail;
        try {
            detail = client.product(productId);
        } catch (CoreException exception) {
            if (exception.errorCode() != CoreErrorCode.PRODUCT_NOT_FOUND) {
                throw exception;
            }
            DetailData notFound = new DetailData(
                    "catalog.detail", "LIVE", "NOT_FOUND",
                    requestEcho(productId, merchantId, optionId), null, clock.instant().toString());
            return new DetailEnvelope(principal.toString(), DEFAULT_CONNECTION_REF, notFound);
        }

        List<Variant> variants = toVariants(detail);
        Offer baseOffer = new Offer(client.merchantId(), client.merchantName(),
                detail.price().amount(), KRW, detail.availability());
        Selection selection = resolveSelection(variants, baseOffer, optionId);
        if ("VALID".equals(selection.status())) {
            variants = withResolvedVariant(variants, selection.selectedOptionId(), selection.offer());
        }

        ProductDetail product = new ProductDetail(
                detail.productId(), detail.title(), "", "", detail.imageUrl() == null ? "" : detail.imageUrl(),
                detail.description(),
                List.of(), selection.offer(), variants,
                selection.optionsComplete(), !variants.isEmpty(),
                selection.status(), selection.selectedOptionId(), null,
                new Shipping("UNKNOWN", null, ""));
        DetailData data = new DetailData(
                "catalog.detail", "LIVE", "FOUND",
                requestEcho(productId, merchantId, optionId), product, clock.instant().toString());
        return new DetailEnvelope(principal.toString(), DEFAULT_CONNECTION_REF, data);
    }

    /**
     * 내부 API(순서 34, 이슈 S15P21A207-417)용 검색. 앱용 {@link #search}와 데이터 원본은 같지만
     * 봉투·페이지네이션 없이 merchant별 항목만 돌려준다 — 비교 묶음·커서는 내부 계약 쪽에서 만든다.
     *
     * <p>{@link MerchantCatalogClient}가 package-private이라 {@code internalapi} 패키지가 Catalog를
     * 직접 부를 수 없어 여기에 둔다({@link #captureForCart}와 같은 이유다).
     */
    public CatalogSearchResult searchForInternal(
            String query, String merchantId, Long minPrice, Long maxPrice) {
        return catalogSearchFanout.search(targets(merchantId), query, minPrice, maxPrice);
    }

    /**
     * 내부 API용 상세. 등록되지 않은 merchant도 {@code PRODUCT_NOT_FOUND}(404)다 — 내부 계약에는
     * merchant 오류 코드가 따로 없고, 호출자 입장에서 "그 merchant의 그 상품이 없다"와 같은 결과다.
     */
    public CatalogProductView detailForInternal(String merchantId, String merchantProductId) {
        MerchantCatalogClient client = clients.get(merchantId);
        if (client == null) {
            throw new CoreException(CoreErrorCode.PRODUCT_NOT_FOUND);
        }
        MerchantCatalogClient.CatalogProductDetail detail = client.product(merchantProductId);
        if (detail == null) {
            throw new CoreException(CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE);
        }
        List<CatalogProductView.OptionGroup> optionGroups = detail.options().stream()
                .map(option -> new CatalogProductView.OptionGroup(
                        option.name(),
                        option.values().stream()
                                .map(value -> new CatalogProductView.OptionValue(
                                        value.optionValueId(), value.label(),
                                        value.priceOr(detail.price().amount()), value.stockQuantity()))
                                .toList()))
                .toList();
        return new CatalogProductView(
                merchantId,
                merchantProductId,
                detail.title(),
                detail.price().amount(),
                detail.price().currency(),
                detail.availability(),
                optionGroups,
                detail.imageUrl());
    }

    /**
     * 장바구니가 담는 시점에 쓸 상품 스냅샷을 만든다(순서 25). {@link MerchantCatalogClient}가
     * package-private이라 cart 패키지가 직접 Catalog를 호출할 수 없어 여기에 둔다.
     *
     * <p>{@code optionValueIds}는 명세상 선택 파라미터다. 비어 있으면 구매 가능한 옵션 값이 정확히 하나일
     * 때만 그것을 자동 선택하고, 둘 이상이면 {@code CART_OPTION_REQUIRED}로 사용자에게 선택을 요구한다.
     *
     * <p>저장되는 {@code merchantVariantId}는 {@code optionValueIds}의 <b>첫 값</b>이다.
     * {@code CheckoutEvaluator}가 Core가 보낸 {@code option_value_ids} 중 {@code ids.get(0)}만 써서
     * variant를 찾고, 체크아웃은 줄당 SKU 하나를 전제한다(mock {@code V6__add_checkout_line_skus.sql}).
     */
    public CartProductSnapshot captureForCart(
            String merchantId, String productId, List<String> optionValueIds) {
        MerchantCatalogClient client = clients.get(merchantId);
        if (client == null) {
            throw new CoreException(
                    CoreErrorCode.INVALID_REQUEST, "등록되지 않은 merchantId입니다.", null, "CHECK_REQUEST");
        }

        MerchantCatalogClient.CatalogProductDetail detail = client.product(productId);
        if (!KRW.equals(detail.price().currency())) {
            throw new CoreException(
                    CoreErrorCode.INVALID_REQUEST, "KRW 상품만 장바구니에 담을 수 있습니다.", null, "CHECK_REQUEST");
        }

        Map<String, CartProductSnapshot.SelectedOption> options = purchasableOptions(detail);
        List<CartProductSnapshot.SelectedOption> selected = resolveSelectedOptions(options, optionValueIds);

        return new CartProductSnapshot(
                detail.productId(),
                selected.get(0).optionValueId(),
                detail.title(),
                productUrl(client, detail.productId()),
                detail.imageUrl(),
                optionPrice(detail, selected.get(0).optionValueId()),
                KRW,
                selected);
    }

    private long optionPrice(MerchantCatalogClient.CatalogProductDetail detail, String optionValueId) {
        return detail.options().stream().flatMap(option -> option.values().stream())
                .filter(value -> value.optionValueId().equals(optionValueId))
                .findFirst().orElseThrow(() -> new CoreException(CoreErrorCode.INVALID_REQUEST))
                .priceOr(detail.price().amount());
    }

    /** {@code optionValueId}(= variant sku) → 옵션 정보. 응답에 이미 구매 가능한 값만 담겨 온다. */
    private Map<String, CartProductSnapshot.SelectedOption> purchasableOptions(
            MerchantCatalogClient.CatalogProductDetail detail) {
        Map<String, CartProductSnapshot.SelectedOption> options = new LinkedHashMap<>();
        for (MerchantCatalogClient.CatalogOption option : detail.options()) {
            for (CatalogOptionValue value : option.values()) {
                options.put(value.optionValueId(), new CartProductSnapshot.SelectedOption(
                        value.optionValueId(), option.name(), value.label()));
            }
        }
        return options;
    }

    private List<CartProductSnapshot.SelectedOption> resolveSelectedOptions(
            Map<String, CartProductSnapshot.SelectedOption> options, List<String> optionValueIds) {
        if (optionValueIds.isEmpty()) {
            if (options.isEmpty()) {
                throw new CoreException(
                        CoreErrorCode.INVALID_REQUEST, "구매 가능한 옵션이 없는 상품입니다.", null, "CHECK_REQUEST");
            }
            if (options.size() > 1) {
                throw new CoreException(CoreErrorCode.CART_OPTION_REQUIRED);
            }
            return List.copyOf(options.values());
        }

        List<CartProductSnapshot.SelectedOption> selected = new ArrayList<>(optionValueIds.size());
        for (String optionValueId : optionValueIds) {
            CartProductSnapshot.SelectedOption option = options.get(optionValueId);
            if (option == null) {
                throw new CoreException(
                        CoreErrorCode.INVALID_REQUEST, "선택할 수 없는 옵션입니다.", null, "CHECK_REQUEST");
            }
            selected.add(option);
        }
        return List.copyOf(selected);
    }

    /** Catalog 응답에 상품 url이 없어 합성한다 — 플레이스홀더다(설계서 §5.3). */
    private String productUrl(MerchantCatalogClient client, String productId) {
        String baseUrl = client.baseUrl() == null ? "" : client.baseUrl();
        return baseUrl + "/products/" + productId;
    }

    /** 검증기가 "선택된 variant의 price·availability는 offer와 같아야 한다"고 요구해 선택된 항목만 갱신한다. */
    private List<Variant> withResolvedVariant(List<Variant> variants, String selectedOptionId, Offer offer) {
        List<Variant> resolved = new ArrayList<>(variants.size());
        for (Variant variant : variants) {
            if (variant.optionId().equals(selectedOptionId)) {
                resolved.add(new Variant(
                        variant.optionId(), variant.label(), offer.price(), offer.availability(), null));
            } else {
                resolved.add(variant);
            }
        }
        return List.copyOf(resolved);
    }

    private List<Variant> toVariants(MerchantCatalogClient.CatalogProductDetail detail) {
        List<Variant> variants = new ArrayList<>();
        for (MerchantCatalogClient.CatalogOption option : detail.options()) {
            for (CatalogOptionValue value : option.values()) {
                variants.add(new Variant(value.optionValueId(), value.label(),
                        value.priceOr(detail.price().amount()), detail.availability(), null));
            }
        }
        return List.copyOf(variants);
    }

    /** {@code ProductDetailService.get()}의 검증 로직을 그대로 만족하도록 상태를 정한다 — 설계서 §3. */
    private Selection resolveSelection(List<Variant> variants, Offer baseOffer, String optionId) {
        if (variants.isEmpty()) {
            if (optionId.isBlank()) {
                return new Selection("NONE", null, baseOffer, true);
            }
            return new Selection("INVALID", null, priceless(baseOffer), true);
        }
        if (optionId.isBlank()) {
            return new Selection("REQUIRED", null, priceless(baseOffer), false);
        }
        for (Variant variant : variants) {
            if (variant.optionId().equals(optionId)) {
                return new Selection("VALID", optionId, new Offer(
                        baseOffer.merchantId(), baseOffer.merchantName(), variant.price(),
                        baseOffer.currency(), variant.availability()), true);
            }
        }
        return new Selection("INVALID", null, priceless(baseOffer), false);
    }

    private Offer priceless(Offer offer) {
        return new Offer(offer.merchantId(), offer.merchantName(), null, offer.currency(), offer.availability());
    }

    private DetailRequestEcho requestEcho(String productId, String merchantId, String optionId) {
        return new DetailRequestEcho(productId, merchantId, optionId.isBlank() ? null : optionId);
    }

    private void sort(List<CatalogProduct> products, String sort) {
        Comparator<CatalogProduct> byPrice = Comparator.comparing(
                product -> product.offers().get(0).price(), Comparator.nullsLast(Comparator.naturalOrder()));
        if ("PRICE_ASC".equals(sort)) {
            products.sort(byPrice);
        } else if ("PRICE_DESC".equals(sort)) {
            products.sort(byPrice.reversed());
        }
    }

    private List<CatalogProduct> paginate(List<CatalogProduct> products, int page, int pageSize) {
        int fromIndex = Math.min((page - 1) * pageSize, products.size());
        int toIndex = Math.min(fromIndex + pageSize, products.size());
        return List.copyOf(products.subList(fromIndex, toIndex));
    }

    /** merchant 필터가 비어 있으면 전부, 등록되지 않은 id면 아무 것도 조회하지 않는다(빈 결과). */
    private List<MerchantCatalogClient> targets(String merchantId) {
        if (merchantId == null || merchantId.isBlank()) {
            return List.copyOf(clients.values());
        }
        MerchantCatalogClient client = clients.get(merchantId);
        return client == null ? List.of() : List.of(client);
    }

    private List<MerchantRef> merchantRefs(List<MerchantCatalogClient> targets) {
        return targets.stream().map(client -> new MerchantRef(client.merchantId(), client.merchantName())).toList();
    }

    private record Selection(String status, String selectedOptionId, Offer offer, boolean optionsComplete) {
    }
}
