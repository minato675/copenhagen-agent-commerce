package com.copenhagen.core.agent.connection;

import java.time.Instant;
import java.util.UUID;

/** 연동 목록 API 응답용 프로젝션. {@code oauth_clients}와 조인한 결과. */
public record AgentConnectionSummary(
        UUID connectionId,
        String clientName,
        String logoUrl,
        String grantedScopesJson,
        Instant connectedAt,
        Instant lastUsedAt) {
}
