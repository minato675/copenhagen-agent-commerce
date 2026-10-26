package com.copenhagen.core.agent.jwt;

public record IssuedAgentToken(String token, long expiresInSeconds) {

    @Override
    public String toString() {
        return "IssuedAgentToken[token=<redacted>, expiresInSeconds=" + expiresInSeconds + ']';
    }
}
