package com.copenhagen.core.orderhistory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.copenhagen.core.cart.CartMerchants;
import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderCursor;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderItemRow;
import com.copenhagen.core.orderhistory.OrderHistoryRepository.OrderRow;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderHistoryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String PAYMENT_LABEL_FALLBACK = "Copenhagen Pay";
    private static final Set<String> ORDER_STATUSES = Set.of(
            "CREATED", "CONFIRMED", "FULFILLING", "SHIPPED", "DELIVERED", "CANCELLED", "FAILED");

    private final OrderHistoryRepository repository;
    private final PiiEncryptor piiEncryptor;
    private final ObjectMapper objectMapper;
    private final CartMerchants cartMerchants;

    public OrderHistoryService(
            OrderHistoryRepository repository, PiiEncryptor piiEncryptor, ObjectMapper objectMapper,
            CartMerchants cartMerchants) {
        this.repository = repository;
        this.piiEncryptor = piiEncryptor;
        this.objectMapper = objectMapper;
        this.cartMerchants = cartMerchants;
    }

    public OrderPageResponse list(long userId, Integer requestedSize, String encodedCursor, String requestedStatus) {
        int pageSize = pageSize(requestedSize);
        String status = status(requestedStatus);
        OrderCursor cursor = decodeCursor(encodedCursor);
        List<OrderRow> fetched = repository.findPage(userId, status, cursor, pageSize + 1);
        boolean hasNext = fetched.size() > pageSize;
        List<OrderRow> page = hasNext ? fetched.subList(0, pageSize) : fetched;
        Map<Long, List<OrderItemRow>> itemsBySnapshot = itemsBySnapshot(page);
        List<OrderResponse> items = page.stream()
                .map(row -> toResponse(row, itemsBySnapshot.getOrDefault(row.checkoutSnapshotId(), List.of())))
                .toList();
        String nextCursor = hasNext ? encodeCursor(page.getLast()) : null;
        return new OrderPageResponse(items, nextCursor);
    }

    public OrderResponse get(long userId, UUID orderId) {
        OrderRow row = repository.findByPublicIdAndUserId(orderId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.ORDER_NOT_FOUND));
        List<OrderItemRow> items = repository.findItemsBySnapshotIds(List.of(row.checkoutSnapshotId()));
        return toResponse(row, items);
    }

    /**
     * v2 목록. 조회·페이지네이션은 v1과 같고 항목만 상품 식별자와 이미지를 더 싣는다(v1 응답은 그대로 둔다).
     */
    public OrderV2PageResponse listV2(
            long userId, Integer requestedSize, String encodedCursor, String requestedStatus) {
        int pageSize = pageSize(requestedSize);
        String status = status(requestedStatus);
        OrderCursor cursor = decodeCursor(encodedCursor);
        List<OrderRow> fetched = repository.findPage(userId, status, cursor, pageSize + 1);
        boolean hasNext = fetched.size() > pageSize;
        List<OrderRow> page = hasNext ? fetched.subList(0, pageSize) : fetched;
        Map<Long, List<OrderItemRow>> itemsBySnapshot = itemsBySnapshot(page);
        List<OrderV2Response> items = page.stream()
                .map(row -> toV2Response(row, itemsBySnapshot.getOrDefault(row.checkoutSnapshotId(), List.of())))
                .toList();
        String nextCursor = hasNext ? encodeCursor(page.getLast()) : null;
        return new OrderV2PageResponse(items, nextCursor);
    }

    public OrderV2Response getV2(long userId, UUID orderId) {
        OrderRow row = repository.findByPublicIdAndUserId(orderId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.ORDER_NOT_FOUND));
        List<OrderItemRow> items = repository.findItemsBySnapshotIds(List.of(row.checkoutSnapshotId()));
        return toV2Response(row, items);
    }

    private Map<Long, List<OrderItemRow>> itemsBySnapshot(List<OrderRow> orders) {
        List<Long> snapshotIds = orders.stream().map(OrderRow::checkoutSnapshotId).toList();
        Map<Long, List<OrderItemRow>> grouped = new LinkedHashMap<>();
        for (OrderItemRow item : repository.findItemsBySnapshotIds(snapshotIds)) {
            grouped.computeIfAbsent(item.checkoutSnapshotId(), ignored -> new java.util.ArrayList<>()).add(item);
        }
        return grouped;
    }

    private OrderResponse toResponse(OrderRow row, List<OrderItemRow> itemRows) {
        List<ItemSummary> items = itemRows.stream()
                .map(item -> new ItemSummary(item.name(), option(item.selectedOptions()), item.quantity()))
                .toList();
        return new OrderResponse(
                row.publicId(),
                title(items.stream().map(ItemSummary::name).toList()),
                row.status(),
                row.amountMinor(),
                row.currency(),
                row.createdAt(),
                items,
                address(row),
                emptyIfNull(row.paymentLabel(), PAYMENT_LABEL_FALLBACK));
    }

    /**
     * v1과 다른 곳은 {@code items}뿐이다. {@code imageUrl}은 V22 이전 주문서 스냅샷에서 {@code null}이고,
     * 그 경우 필드를 비운다 — 빈 문자열로 바꾸면 "이미지가 있다"는 오해를 준다.
     */
    private OrderV2Response toV2Response(OrderRow row, List<OrderItemRow> itemRows) {
        List<ItemDetail> items = itemRows.stream()
                .map(item -> new ItemDetail(
                        cartMerchants.merchantId(item.merchantRefId()),
                        item.merchantProductId(),
                        item.merchantVariantId(),
                        item.name(),
                        option(item.selectedOptions()),
                        item.imageUrl(),
                        item.quantity()))
                .toList();
        return new OrderV2Response(
                row.publicId(),
                title(items.stream().map(ItemDetail::name).toList()),
                row.status(),
                row.amountMinor(),
                row.currency(),
                row.createdAt(),
                items,
                address(row),
                emptyIfNull(row.paymentLabel(), PAYMENT_LABEL_FALLBACK));
    }

    private AddressSnapshot address(OrderRow row) {
        return new AddressSnapshot(
                emptyIfNull(piiEncryptor.decrypt(row.recipient())),
                emptyIfNull(piiEncryptor.decrypt(row.phone())),
                emptyIfNull(piiEncryptor.decrypt(row.postalCode())),
                emptyIfNull(piiEncryptor.decrypt(row.address())),
                emptyIfNull(piiEncryptor.decrypt(row.detail())),
                emptyIfNull(piiEncryptor.decrypt(row.memo())));
    }

    private String option(String selectedOptions) {
        if (selectedOptions == null || selectedOptions.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readValue(selectedOptions, new TypeReference<List<SelectedOption>>() { })
                    .stream()
                    .map(SelectedOption::label)
                    .filter(Objects::nonNull)
                    .filter(label -> !label.isBlank())
                    .collect(java.util.stream.Collectors.joining(" / "));
        } catch (JacksonException exception) {
            throw new CoreException(
                    CoreErrorCode.INTERNAL_ERROR,
                    CoreErrorCode.INTERNAL_ERROR.defaultMessage(),
                    null,
                    CoreErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    private static String title(List<String> names) {
        if (names.isEmpty()) {
            return "주문 상품";
        }
        return names.size() == 1
                ? names.getFirst()
                : names.getFirst() + " 외 " + (names.size() - 1) + "건";
    }

    private static int pageSize(Integer requestedSize) {
        int pageSize = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new CoreException(CoreErrorCode.INVALID_REQUEST);
        }
        return pageSize;
    }

    private static String status(String requestedStatus) {
        if (requestedStatus == null || requestedStatus.isBlank()) {
            return null;
        }
        if (!ORDER_STATUSES.contains(requestedStatus)) {
            throw new CoreException(CoreErrorCode.INVALID_REQUEST);
        }
        return requestedStatus;
    }

    private static String encodeCursor(OrderRow row) {
        String value = row.createdAt().getEpochSecond() + ":" + row.createdAt().getNano() + ":" + row.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static OrderCursor decodeCursor(String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encodedCursor), StandardCharsets.UTF_8)
                    .split(":", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("cursor parts");
            }
            return new OrderCursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])),
                    Long.parseLong(parts[2]));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new CoreException(
                    CoreErrorCode.INVALID_REQUEST,
                    CoreErrorCode.INVALID_REQUEST.defaultMessage(),
                    null,
                    CoreErrorCode.INVALID_REQUEST.defaultNextAction(),
                    exception);
        }
    }

    private static String emptyIfNull(String value) {
        return emptyIfNull(value, "");
    }

    private static String emptyIfNull(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private record SelectedOption(String optionValueId, String name, String label) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderPageResponse(List<OrderResponse> items, String nextCursor) {
    }

    public record OrderResponse(
            UUID orderId,
            String title,
            String status,
            long amountMinor,
            String currency,
            Instant createdAt,
            List<ItemSummary> items,
            AddressSnapshot address,
            String paymentLabel) {
    }

    public record ItemSummary(String name, String option, int quantity) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderV2PageResponse(List<OrderV2Response> items, String nextCursor) {
    }

    /** v1의 {@code OrderResponse}와 항목 타입만 다르다. 나머지 필드는 이름·의미가 같다. */
    public record OrderV2Response(
            UUID orderId,
            String title,
            String status,
            long amountMinor,
            String currency,
            Instant createdAt,
            List<ItemDetail> items,
            AddressSnapshot address,
            String paymentLabel) {
    }

    /**
     * v2 주문 항목. {@code merchantId}·{@code merchantProductId}·{@code merchantVariantId}로 상품을
     * 다시 찾을 수 있고, {@code imageUrl}은 주문 당시 캡처한 이미지 주소다(없으면 {@code null}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemDetail(
            String merchantId,
            String merchantProductId,
            String merchantVariantId,
            String name,
            String option,
            String imageUrl,
            int quantity) {
    }

    public record AddressSnapshot(
            String recipient,
            String phone,
            String postalCode,
            String address,
            String detail,
            String memo) {
    }
}
