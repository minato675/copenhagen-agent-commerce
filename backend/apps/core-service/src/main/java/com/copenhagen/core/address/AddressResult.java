package com.copenhagen.core.address;

import java.util.UUID;

public record AddressResult(
        UUID addressId,
        String recipientName,
        String recipientPhone,
        String postalCode,
        String addressLine1,
        String addressLine2,
        String deliveryRequest,
        boolean isDefault) {
}
