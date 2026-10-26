package com.copenhagen.core.agent.refreshtoken;

import java.time.Instant;

public record IssuedAgentRefreshToken(String plainToken, Instant expiresAt) {

    @Override
    public String toString() {
        return "IssuedAgentRefreshToken[plainToken=<redacted>, expiresAt=" + expiresAt + ']';
    }
}
