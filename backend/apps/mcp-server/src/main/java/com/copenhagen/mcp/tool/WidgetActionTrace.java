package com.copenhagen.mcp.tool;

import com.copenhagen.mcp.auth.McpIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Correlation only: never records raw references, identities, tokens or tool arguments. */
public final class WidgetActionTrace {
    private static final Logger log = LoggerFactory.getLogger(WidgetActionTrace.class);
    private static final String INSTANCE = UUID.randomUUID().toString();

    private WidgetActionTrace() {
    }

    public static void record(String reason, String reference, McpIdentity identity, String tool, long ageMs) {
        log.info("MCP action reference: requestId={}, instance={}, reason={}, referenceHash={}, "
                        + "userHash={}, connectionHash={}, targetTool={}, ageMs={}",
                MDC.get("mcpRequestId"), INSTANCE, reason, hash(reference),
                hash(identity.publicUserRef()), hash(identity.connectionRef()), tool, ageMs);
    }

    private static String hash(String value) {
        if (value == null) {
            return "-";
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
