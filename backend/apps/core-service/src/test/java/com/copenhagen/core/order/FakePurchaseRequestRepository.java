package com.copenhagen.core.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 인메모리 구매 요청 저장소. 적재된 행을 그대로 들여다볼 수 있게 열어 둔다. */
public class FakePurchaseRequestRepository implements PurchaseRequestRepository {

    public final List<Row> rows = new ArrayList<>();

    private long nextId = 1;

    @Override
    public PurchaseRequest insertCreatingCheckouts(
            UUID publicId, long userId, long cartId, String requestChannel, String policyVersion, Instant now) {
        long id = nextId++;
        rows.add(new Row(id, publicId, userId, cartId, requestChannel,
                PurchaseRequest.STATUS_CREATING_CHECKOUTS, policyVersion, null, null, null));
        return new PurchaseRequest(
                id, publicId, userId, cartId, PurchaseRequest.STATUS_CREATING_CHECKOUTS);
    }

    @Override
    public PurchaseRequest insertPendingAddress(
            UUID publicId,
            long userId,
            long cartId,
            long agentConnectionId,
            String policyVersion,
            int cartVersion,
            Instant expiresAt,
            Instant now) {
        long id = nextId++;
        rows.add(new Row(id, publicId, userId, cartId, PurchaseRequest.CHANNEL_MCP,
                PurchaseRequest.STATUS_PENDING_ADDRESS, policyVersion, agentConnectionId, cartVersion,
                expiresAt));
        return new PurchaseRequest(
                id, publicId, userId, cartId, PurchaseRequest.STATUS_PENDING_ADDRESS);
    }

    @Override
    public void markFailed(long id, Instant now) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).id() == id) {
                Row row = rows.get(index);
                rows.set(index, new Row(row.id(), row.publicId(), row.userId(), row.cartId(),
                        row.requestChannel(), PurchaseRequest.STATUS_FAILED, row.policyVersion(),
                        row.agentConnectionId(), row.cartVersion(), row.expiresAt()));
            }
        }
    }

    public record Row(
            long id,
            UUID publicId,
            long userId,
            long cartId,
            String requestChannel,
            String status,
            String policyVersion,
            Long agentConnectionId,
            Integer cartVersion,
            Instant expiresAt) {
    }
}
