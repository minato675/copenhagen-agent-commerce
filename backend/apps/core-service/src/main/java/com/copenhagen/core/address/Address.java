package com.copenhagen.core.address;

import java.time.Instant;
import java.util.UUID;

import com.copenhagen.core.common.crypto.EncryptedPii;

public record Address(
        long id,
        UUID publicId,
        long userId,
        EncryptedPii recipientNameEncrypted,
        EncryptedPii recipientPhoneEncrypted,
        EncryptedPii postalCodeEncrypted,
        EncryptedPii addressLine1Encrypted,
        EncryptedPii addressLine2Encrypted,
        EncryptedPii deliveryRequestEncrypted,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt) {
}
