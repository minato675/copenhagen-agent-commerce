package com.copenhagen.core.agent.connection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/** AGENT-04: 연동 상태 확인·해제. */
@RestController
@RequestMapping("/api/core/v1/users/me/agent-connections")
public class AgentConnectionController {

    private final AgentConnectionService connectionService;
    private final UserRepository userRepository;

    public AgentConnectionController(AgentConnectionService connectionService, UserRepository userRepository) {
        this.connectionService = connectionService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<AgentConnectionResponse> list(HttpServletRequest request) {
        long userId = requireUserId(request);
        return connectionService.list(userId).stream()
                .map(summary -> AgentConnectionResponse.from(summary, connectionService.readScopes(
                        summary.grantedScopesJson())))
                .toList();
    }

    @DeleteMapping("/{connectionId}")
    public ResponseEntity<Void> revoke(HttpServletRequest request, @PathVariable UUID connectionId) {
        long userId = requireUserId(request);
        connectionService.revoke(userId, connectionId);
        return ResponseEntity.noContent().build();
    }

    private long requireUserId(HttpServletRequest request) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return userRepository.findByPublicId(principal)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID))
                .id();
    }

    public record AgentConnectionResponse(
            UUID connectionId,
            String clientName,
            String logoUrl,
            List<String> scopes,
            Instant connectedAt,
            Instant lastUsedAt) {

        static AgentConnectionResponse from(AgentConnectionSummary summary, List<String> scopes) {
            return new AgentConnectionResponse(
                    summary.connectionId(), summary.clientName(), summary.logoUrl(), scopes,
                    summary.connectedAt(), summary.lastUsedAt());
        }
    }
}
