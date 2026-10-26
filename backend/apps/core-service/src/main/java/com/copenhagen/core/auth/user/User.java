package com.copenhagen.core.auth.user;

import java.time.Instant;
import java.util.UUID;

import com.copenhagen.core.common.crypto.EncryptedPii;

public record User(
        long id,
        UUID publicId,
        String name,
        UserStatus status,
        EncryptedPii emailEncrypted,
        String emailHash,
        boolean emailVerified,
        EncryptedPii phoneEncrypted,
        String phoneHash,
        Instant deletedAt,
        Instant pwChangedAt,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt) {
}
