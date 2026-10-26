package com.copenhagen.core.auth.oauthaccount;

import java.time.Instant;

public record OauthAccount(
        long id,
        long userId,
        OauthProvider provider,
        String providerSubject,
        Instant createdAt,
        Instant updatedAt) {
}
