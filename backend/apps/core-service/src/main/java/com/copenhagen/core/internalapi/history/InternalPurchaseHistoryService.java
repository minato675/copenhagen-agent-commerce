package com.copenhagen.core.internalapi.history;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;
import com.copenhagen.core.internalapi.history.PurchaseHistoryRepository.PurchaseHistoryRow;
import com.copenhagen.core.product.CartProductSnapshot;

/**
 * 최근 6개월 구매내역을 상품 단위로 묶어 돌려준다(설계서 §4, 이슈 S15P21A207-490).
 *
 * <p>추천은 하지 않는다 — LLM이 이 결과를 근거로 {@code search_products}를 이어 호출한다. 배송지·
 * 결제수단·주문 총액·주문 ID는 추천에 필요 없어 계약에서 뺐다(D-F).
 */
@Service
public class InternalPurchaseHistoryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private static final int PERIOD_MONTHS = 6;
    private static final ZoneId PERIOD_ZONE = ZoneId.of("Asia/Seoul");

    /** mcp-server {@code CommerceProjection.id()}가 받는 문자 집합. 벗어난 ID는 응답에서 null이다(D-H). */
    private static final Pattern MCP_ID = Pattern.compile("[A-Za-z0-9_:-]{1,96}");

    private static final TypeReference<List<CartProductSnapshot.SelectedOption>> SELECTED_OPTIONS_TYPE =
            new TypeReference<>() { };

    private final PurchaseHistoryRepository repository;
    private final CartMerchants cartMerchants;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InternalPurchaseHistoryService(
            PurchaseHistoryRepository repository,
            CartMerchants cartMerchants,
            ObjectMapper objectMapper,
            Clock coreClock) {
        this.repository = repository;
        this.cartMerchants = cartMerchants;
        this.objectMapper = objectMapper;
        this.clock = coreClock;
    }

    @Transactional(readOnly = true)
    public InternalPurchaseHistoryResponse get(InternalRequestContext context, Integer pageSize, String cursor) {
        int size = pageSize(pageSize);
        Instant periodStart = periodStart();
        List<PurchaseHistoryRow> rows = repository.findPage(
                context.userId(), periodStart, PurchaseHistoryCursor.decode(objectMapper, cursor), size + 1);

        List<InternalPurchaseHistoryResponse.Item> items = new ArrayList<>();
        for (PurchaseHistoryRow row : rows.subList(0, Math.min(rows.size(), size))) {
            items.add(item(row));
        }
        return new InternalPurchaseHistoryResponse(periodStart.toString(), items, nextCursor(rows, size));
    }

    private int pageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InternalApiException(
                    InternalErrorCode.INVALID_ARGUMENT, "pageSize는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
        return pageSize;
    }

    /** 기간 시작은 커서에 담지 않고 매 요청 다시 계산한다 — 페이지 사이 경계 이동은 허용한다(§4.4). */
    private Instant periodStart() {
        return ZonedDateTime.ofInstant(clock.instant(), PERIOD_ZONE)
                .minusMonths(PERIOD_MONTHS)
                .toInstant()
                .truncatedTo(ChronoUnit.SECONDS);
    }

    private InternalPurchaseHistoryResponse.Item item(PurchaseHistoryRow row) {
        return new InternalPurchaseHistoryResponse.Item(
                cartMerchants.merchantId(row.merchantRefId()),
                row.merchantName(),
                mcpId(row.merchantProductId()),
                mcpId(row.merchantVariantId()),
                row.productName(),
                optionLabel(row.selectedOptions()),
                row.purchaseCount(),
                row.totalQuantity(),
                row.unitPriceAmount(),
                row.currency(),
                row.lastPurchasedAt().toString());
    }

    /**
     * 커서에는 D-H로 null이 되기 전의 DB 원본 ID를 담는다. 마지막으로 응답에 실은 행이 기준점이다.
     */
    private String nextCursor(List<PurchaseHistoryRow> rows, int size) {
        if (rows.size() <= size) {
            return null;
        }
        PurchaseHistoryRow last = rows.get(size - 1);
        return PurchaseHistoryCursor.encode(objectMapper, PurchaseHistoryCursor.of(
                last.lastPurchasedAt(), last.merchantRefId(), last.merchantProductId()));
    }

    private static String mcpId(String value) {
        return value != null && MCP_ID.matcher(value).matches() ? value : null;
    }

    /** {@code OrderHistoryService.option()}과 같은 규칙. 앱 계약을 건드리지 않으려고 로직만 맞춘다. */
    private String optionLabel(String selectedOptions) {
        if (selectedOptions == null || selectedOptions.isBlank()) {
            return "";
        }
        return objectMapper.readValue(selectedOptions, SELECTED_OPTIONS_TYPE).stream()
                .map(CartProductSnapshot.SelectedOption::label)
                .filter(Objects::nonNull)
                .filter(label -> !label.isBlank())
                .collect(Collectors.joining(" / "));
    }
}
