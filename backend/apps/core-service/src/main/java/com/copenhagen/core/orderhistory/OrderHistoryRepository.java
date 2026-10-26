package com.copenhagen.core.orderhistory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.copenhagen.core.common.crypto.EncryptedPii;

public interface OrderHistoryRepository {

    List<OrderRow> findPage(long userId, String status, OrderCursor cursor, int limit);

    Optional<OrderRow> findByPublicIdAndUserId(UUID publicId, long userId);

    List<OrderItemRow> findItemsBySnapshotIds(List<Long> checkoutSnapshotIds);

    record OrderCursor(Instant createdAt, long orderId) {
    }

    record OrderRow(
            long id,
            UUID publicId,
            long checkoutSnapshotId,
            String status,
            long amountMinor,
            String currency,
            String paymentLabel,
            Instant createdAt,
            EncryptedPii recipient,
            EncryptedPii phone,
            EncryptedPii postalCode,
            EncryptedPii address,
            EncryptedPii detail,
            EncryptedPii memo) {
    }

    /**
     * {@code merchantRefId}는 {@code merchants.id}다. 응답에 쓰는 문자열 merchantId는
     * {@code CartMerchants.merchantId(long)}로 되돌린다.
     *
     * <p>{@code imageUrl}은 V22 이전에 만들어진 주문서 스냅샷에서는 {@code null}이다.
     */
    record OrderItemRow(
            long checkoutSnapshotId,
            long merchantRefId,
            String merchantProductId,
            String merchantVariantId,
            String name,
            String imageUrl,
            String selectedOptions,
            int quantity) {
    }
}
