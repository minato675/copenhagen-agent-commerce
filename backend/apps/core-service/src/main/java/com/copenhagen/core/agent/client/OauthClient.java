package com.copenhagen.core.agent.client;

public record OauthClient(
        long id,
        String clientId,
        String clientSecretHash,
        String clientName,
        String clientType,
        String redirectUris,
        String allowedScopes,
        String logoUrl,
        String status,
        String clientJwks) {

    public static final String TYPE_PUBLIC = "PUBLIC";
    public static final String TYPE_CONFIDENTIAL = "CONFIDENTIAL";
    public static final String STATUS_ACTIVE = "ACTIVE";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean isPublic() {
        return TYPE_PUBLIC.equals(clientType);
    }
}
