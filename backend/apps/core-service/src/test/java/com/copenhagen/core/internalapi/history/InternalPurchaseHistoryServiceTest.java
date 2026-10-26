package com.copenhagen.core.internalapi.history;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.internalapi.auth.InternalRequestContext;
import com.copenhagen.core.internalapi.error.InternalApiException;
import com.copenhagen.core.internalapi.error.InternalErrorCode;
import com.copenhagen.core.internalapi.history.PurchaseHistoryRepository.PurchaseHistoryRow;
import com.copenhagen.core.product.MerchantsProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 설계서 §4·§5 — 페이지 크기·기간·옵션 라벨·D-H(ID 패턴)·커서 생성. */
class InternalPurchaseHistoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:30:00Z");
    private static final InternalRequestContext CONTEXT =
            new InternalRequestContext(7L, UUID.randomUUID(), 3L, Set.of("purchase:handoff"), null);

    private final FakePurchaseHistoryRepository repository = new FakePurchaseHistoryRepository();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InternalPurchaseHistoryService service = new InternalPurchaseHistoryService(
            repository,
            new CartMerchants(new MerchantsProperties(List.of(new MerchantsProperties.MerchantConfig(
                    "merchant-a", 1L, "A몰", URI.create("http://merchant:8080"),
                    Duration.ofSeconds(1), Duration.ofSeconds(5))))),
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("pageSize를 생략하면 20건을 읽고 기간은 Asia/Seoul 기준 6개월 전이다")
    void get_usesDefaultPageSizeAndSixMonthPeriod() {
        repository.rows = List.of(row("P-1001", "P-1001-BLK-M", 2, 3));

        InternalPurchaseHistoryResponse response = service.get(CONTEXT, null, null);

        assertEquals(21, repository.limit);
        assertEquals("2026-03-22T00:30:00Z", response.periodStart());
        assertEquals(Instant.parse("2026-03-22T00:30:00Z"), repository.periodStart);
        assertEquals(7L, repository.userId);
        assertNull(repository.cursor);
        assertEquals("merchant-a", response.items().getFirst().merchantId());
        assertEquals("블랙 / M", response.items().getFirst().optionLabel());
        assertNull(response.nextCursor());
    }

    @Test
    @DisplayName("pageSize가 허용 범위를 벗어나면 INVALID_ARGUMENT다")
    void get_rejectsPageSizeOutOfRange() {
        assertEquals(InternalErrorCode.INVALID_ARGUMENT, assertThrows(InternalApiException.class,
                () -> service.get(CONTEXT, 0, null)).errorCode());
        assertEquals(InternalErrorCode.INVALID_ARGUMENT, assertThrows(InternalApiException.class,
                () -> service.get(CONTEXT, 51, null)).errorCode());
        assertEquals(51, serviceReadsLimitFor(50));
    }

    @Test
    @DisplayName("MCP ID 패턴을 벗어난 식별자는 null로 내리고 항목은 남긴다")
    void get_nullsIdentifiersOutsideMcpPattern() {
        repository.rows = List.of(row("상품/1001", "P-1001-BLK-M", 1, 1));

        InternalPurchaseHistoryResponse.Item item = service.get(CONTEXT, null, null).items().getFirst();

        assertNull(item.merchantProductId());
        assertEquals("P-1001-BLK-M", item.merchantVariantId());
        assertEquals("오버핏 맨투맨", item.productName());
        assertEquals(1, item.purchaseCount());
    }

    @Test
    @DisplayName("옵션이 없으면 빈 문자열이다")
    void get_emptyOptionLabelWhenNoOptions() {
        repository.rows = List.of(new PurchaseHistoryRow(1L, "A몰", "P-2", "P-2-1", "머그컵",
                "[]", 1, 1, 9000L, "KRW", Instant.parse("2026-09-01T00:00:00Z")));

        assertEquals("", service.get(CONTEXT, null, null).items().getFirst().optionLabel());
    }

    @Test
    @DisplayName("다음 페이지가 있으면 마지막으로 실은 행을 가리키는 커서를 준다")
    void get_buildsCursorFromLastIncludedRow() {
        repository.rows = List.of(
                row("P-1", "P-1-1", 1, 1),
                row("P-2", "P-2-1", 1, 1),
                row("P-3", "P-3-1", 1, 1));

        InternalPurchaseHistoryResponse response = service.get(CONTEXT, 2, null);

        assertEquals(2, response.items().size());
        assertNotNull(response.nextCursor());
        PurchaseHistoryCursor cursor = PurchaseHistoryCursor.decode(objectMapper, response.nextCursor());
        assertEquals("P-2", cursor.merchantProductId());
        assertEquals(1L, cursor.merchantRefId());
    }

    @Test
    @DisplayName("커서에는 null로 내리기 전의 원본 식별자를 담는다")
    void get_cursorKeepsRawIdentifier() {
        repository.rows = List.of(row("상품/1001", "P-1-1", 1, 1), row("P-2", "P-2-1", 1, 1));

        String nextCursor = service.get(CONTEXT, 1, null).nextCursor();

        assertEquals("상품/1001", PurchaseHistoryCursor.decode(objectMapper, nextCursor).merchantProductId());
    }

    @Test
    @DisplayName("전달한 커서는 그대로 저장소로 넘어간다")
    void get_passesDecodedCursorToRepository() {
        repository.rows = List.of();
        String cursor = PurchaseHistoryCursor.encode(objectMapper,
                PurchaseHistoryCursor.of(Instant.parse("2026-09-01T00:00:00Z"), 1L, "P-9"));

        InternalPurchaseHistoryResponse response = service.get(CONTEXT, null, cursor);

        assertEquals("P-9", repository.cursor.merchantProductId());
        assertEquals(List.of(), response.items());
        assertNull(response.nextCursor());
    }

    private int serviceReadsLimitFor(int pageSize) {
        repository.rows = List.of();
        service.get(CONTEXT, pageSize, null);
        return repository.limit;
    }

    private static PurchaseHistoryRow row(String productId, String variantId, int purchaseCount, long quantity) {
        return new PurchaseHistoryRow(1L, "A몰", productId, variantId, "오버핏 맨투맨",
                "[{\"optionValueId\":\"v1\",\"name\":\"색상\",\"label\":\"블랙\"},"
                        + "{\"optionValueId\":\"v2\",\"name\":\"사이즈\",\"label\":\"M\"}]",
                purchaseCount, quantity, 39000L, "KRW", Instant.parse("2026-09-10T05:12:33Z"));
    }

    private static final class FakePurchaseHistoryRepository implements PurchaseHistoryRepository {

        private List<PurchaseHistoryRow> rows = new ArrayList<>();
        private long userId;
        private Instant periodStart;
        private PurchaseHistoryCursor cursor;
        private int limit;

        @Override
        public List<PurchaseHistoryRow> findPage(
                long userId, Instant periodStart, PurchaseHistoryCursor cursor, int limit) {
            this.userId = userId;
            this.periodStart = periodStart;
            this.cursor = cursor;
            this.limit = limit;
            return rows.size() > limit ? rows.subList(0, limit) : rows;
        }
    }
}
