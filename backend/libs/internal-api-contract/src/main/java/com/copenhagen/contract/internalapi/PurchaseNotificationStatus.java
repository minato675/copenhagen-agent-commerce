package com.copenhagen.contract.internalapi;

public record PurchaseNotificationStatus(String eventId, String status, String errorCode) {
}
