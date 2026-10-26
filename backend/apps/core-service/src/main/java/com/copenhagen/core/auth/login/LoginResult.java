package com.copenhagen.core.auth.login;

import java.util.UUID;

public record LoginResult(UUID userId, long userInternalId, String loginId, String name) {
}
