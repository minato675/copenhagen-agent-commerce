package com.copenhagen.core.orderhistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderCursor;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderItemRow;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderRow;
import com.copenhagen.core.product.MerchantsProperties;

import tools.jackson.databind.json.JsonMapper;

class OrderHistoryServiceTest {

    private static final long USER_ID = 7L;

    @Test
    void list_usesStableCursorAndProjectsSnapshotFields() {
        OrderHistoryRepository repository = org.mockito.Mockito.mock(OrderHistoryRepository.class);
        PiiEncryptor encryptor = encryptor();
        OrderHistoryService service =
                new OrderHistoryService(repository, encryptor, JsonMapper.builder().build(), merchants());
        Instant createdAt = Instant.parse("2026-09-18T08:00:00.123456789Z");
        OrderRow first = row(encryptor, 30L, UUID.randomUUID(), createdAt, null);
        OrderRow second = row(encryptor, 29L, UUID.randomUUID(), createdAt.minusSeconds(1), "VISA **** 1234");
        when(repository.findPage(eq(USER_ID), eq("CREATED"), any(), eq(2))).thenReturn(List.of(first, second));
        when(repository.findItemsBySnapshotIds(List.of(100L))).thenReturn(List.of(
                new OrderItemRow(100L, 1L, "product-101", "product-101-270", "상품 A",
                        "https://cdn.example.test/product-101.jpg",
                        "[{\"label\":\"화이트\"},{\"label\":\"M\"}]", 1)));

        OrderHistoryService.OrderPageResponse response = service.list(USER_ID, 1, null, "CREATED");

        assertEquals(1, response.items().size());
        assertEquals("상품 A", response.items().getFirst().title());
        assertEquals("화이트 / M", response.items().getFirst().items().getFirst().option());
        assertEquals("홍길동", response.items().getFirst().address().recipient());
        assertEquals("Copenhagen Pay", response.items().getFirst().paymentLabel());
        assertFalse(response.nextCursor().isBlank());

        when(repository.findPage(eq(USER_ID), eq("CREATED"), any(), eq(2))).thenReturn(List.of());
        service.list(USER_ID, 1, response.nextCursor(), "CREATED");

        ArgumentCaptor<OrderCursor> cursor = ArgumentCaptor.forClass(OrderCursor.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2))
                .findPage(eq(USER_ID), eq("CREATED"), cursor.capture(), eq(2));
        assertEquals(createdAt, cursor.getAllValues().get(1).createdAt());
        assertEquals(30L, cursor.getAllValues().get(1).orderId());
    }

    @Test
    void listV2_addsMerchantIdentifiersAndImageWhileV1StaysUnchanged() {
        OrderHistoryRepository repository = org.mockito.Mockito.mock(OrderHistoryRepository.class);
        PiiEncryptor encryptor = encryptor();
        OrderHistoryService service =
                new OrderHistoryService(repository, encryptor, JsonMapper.builder().build(), merchants());
        Instant createdAt = Instant.parse("2026-09-18T08:00:00.123456789Z");
        OrderRow order = row(encryptor, 30L, UUID.randomUUID(), createdAt, "VISA **** 1234");
        when(repository.findPage(eq(USER_ID), eq(null), any(), eq(21))).thenReturn(List.of(order));
        when(repository.findItemsBySnapshotIds(List.of(100L))).thenReturn(List.of(
                new OrderItemRow(100L, 1L, "product-101", "product-101-270", "상품 A",
                        "https://cdn.example.test/product-101.jpg",
                        "[{\"label\":\"화이트\"},{\"label\":\"M\"}]", 1)));

        OrderHistoryService.ItemDetail item =
                service.listV2(USER_ID, null, null, null).items().getFirst().items().getFirst();

        assertEquals("merchant-a", item.merchantId());
        assertEquals("product-101", item.merchantProductId());
        assertEquals("product-101-270", item.merchantVariantId());
        assertEquals("https://cdn.example.test/product-101.jpg", item.imageUrl());
        assertEquals("화이트 / M", item.option());
        assertEquals(1, item.quantity());
        // v1은 같은 행을 읽어도 이름·옵션·수량만 내준다.
        assertEquals(
                new OrderHistoryService.ItemSummary("상품 A", "화이트 / M", 1),
                service.list(USER_ID, null, null, null).items().getFirst().items().getFirst());
    }

    @Test
    void listV2_keepsImageNullForSnapshotsTakenBeforeTheColumnExisted() {
        OrderHistoryRepository repository = org.mockito.Mockito.mock(OrderHistoryRepository.class);
        PiiEncryptor encryptor = encryptor();
        OrderHistoryService service =
                new OrderHistoryService(repository, encryptor, JsonMapper.builder().build(), merchants());
        UUID orderId = UUID.randomUUID();
        OrderRow order = row(encryptor, 30L, orderId, Instant.parse("2026-09-18T08:00:00Z"), null);
        when(repository.findByPublicIdAndUserId(orderId, USER_ID)).thenReturn(java.util.Optional.of(order));
        when(repository.findItemsBySnapshotIds(List.of(100L))).thenReturn(List.of(
                new OrderItemRow(100L, 99L, "product-101", "product-101-270", "상품 A", null, "[]", 2)));

        OrderHistoryService.ItemDetail item = service.getV2(USER_ID, orderId).items().getFirst();

        assertNull(item.imageUrl());
        assertEquals("", item.option());
        // 설정에서 사라진 merchant는 숫자를 그대로 노출한다(CartMerchants.merchantId).
        assertEquals("99", item.merchantId());
    }

    /** v2는 v1과 같은 검증기를 쓴다. 경로가 갈라져도 경계가 함께 움직이는지 확인한다. */
    @Test
    void listV2_appliesSamePageSizeAndCursorBoundariesAsV1() {
        OrderHistoryRepository repository = org.mockito.Mockito.mock(OrderHistoryRepository.class);
        OrderHistoryService service =
                new OrderHistoryService(repository, encryptor(), JsonMapper.builder().build(), merchants());
        when(repository.findPage(eq(USER_ID), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        assertThrows(CoreException.class, () -> service.listV2(USER_ID, 0, null, null));
        assertThrows(CoreException.class, () -> service.listV2(USER_ID, 101, null, null));
        assertThrows(CoreException.class, () -> service.listV2(USER_ID, 20, "not-a-cursor", null));
        assertThrows(CoreException.class, () -> service.listV2(USER_ID, 20, null, "PAID"));

        // 경계 안쪽은 통과해야 한다 — 1과 100, 그리고 빈 문자열 status(= 필터 없음).
        service.listV2(USER_ID, 1, null, null);
        service.listV2(USER_ID, 100, null, "");
        org.mockito.Mockito.verify(repository).findPage(eq(USER_ID), eq(null), eq(null), eq(2));
        org.mockito.Mockito.verify(repository).findPage(eq(USER_ID), eq(null), eq(null), eq(101));
    }

    @Test
    void list_rejectsMalformedCursorAndUnknownStatus() {
        OrderHistoryRepository repository = org.mockito.Mockito.mock(OrderHistoryRepository.class);
        OrderHistoryService service =
                new OrderHistoryService(repository, encryptor(), JsonMapper.builder().build(), merchants());

        assertThrows(CoreException.class, () -> service.list(USER_ID, 20, "not-a-cursor", null));
        assertThrows(CoreException.class, () -> service.list(USER_ID, 20, null, "PAID"));
    }

    private static OrderRow row(
            PiiEncryptor encryptor, long id, UUID publicId, Instant createdAt, String paymentLabel) {
        return new OrderRow(
                id,
                publicId,
                100L,
                "CREATED",
                87000L,
                "KRW",
                paymentLabel,
                createdAt,
                encryptor.encrypt("홍길동"),
                encryptor.encrypt("01012345678"),
                encryptor.encrypt("06236"),
                encryptor.encrypt("서울특별시 강남구 테헤란로 1"),
                encryptor.encrypt("101동 1001호"),
                encryptor.encrypt("문 앞에 놓아주세요"));
    }

    private static PiiEncryptor encryptor() {
        return new PiiEncryptor(Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static CartMerchants merchants() {
        return new CartMerchants(new MerchantsProperties(List.of(new MerchantsProperties.MerchantConfig(
                "merchant-a", 1L, "A몰", URI.create("http://merchant:8080"),
                Duration.ofSeconds(1), Duration.ofSeconds(5)))));
    }
}
