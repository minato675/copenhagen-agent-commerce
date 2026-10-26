package com.copenhagen.core.agent.refreshtoken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

/**
 * Agent 연동의 Refresh Token 발급·회전(rotation)·재사용 탐지를 담당한다.
 * {@code auth.refreshtoken.RefreshTokenService}와 동일한 패턴(SHA-256 해시 저장, 회전 시 이전 토큰
 * 폐기)이며, TTL은 별도 근거가 없어 기존 사용자 Refresh Token과 같은 14일로 통일한다(설계서 §4.4).
 */
@Service
public class AgentRefreshTokenService {

    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);
    private static final int TOKEN_BYTES = 32;

    private final AgentRefreshTokenRepository repository;
    private final AgentRefreshTokenBreachService breachService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AgentRefreshTokenService(
            AgentRefreshTokenRepository repository,
            AgentRefreshTokenBreachService breachService,
            Clock clock) {
        this.repository = repository;
        this.breachService = breachService;
        this.clock = clock;
    }

    @Transactional
    public IssuedAgentRefreshToken issue(long connectionId, String scopesJson) {
        return insertNewToken(connectionId, scopesJson, clock.instant());
    }

    /** 회전 대상 행을 잠근 채로 검증해 반환한다. 연동 활성 여부 확인은 호출자가 이어서 수행한다. */
    // 검증 거절은 호출자가 invalid_grant로 변환한다. 쓰기 없이 거절된 트랜잭션을 rollback-only로
    // 만들지 않는다. 재사용 탐지에 따른 폐기는 breachService의 독립 트랜잭션에서 커밋한다.
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = CoreException.class)
    public AgentRefreshToken verifyForRotation(String plainToken) {
        AgentRefreshToken current = repository.findByTokenHashForUpdate(sha256Hex(plainToken))
                .orElseThrow(() -> new CoreException(CoreErrorCode.OAUTH_INVALID_GRANT));

        if (current.revokedAt() != null) {
            breachService.revokeAllForConnection(current.connectionId());
            throw new CoreException(CoreErrorCode.OAUTH_INVALID_GRANT);
        }
        if (!current.expiresAt().isAfter(clock.instant())) {
            throw new CoreException(CoreErrorCode.OAUTH_INVALID_GRANT);
        }
        return current;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedAgentRefreshToken rotate(AgentRefreshToken current) {
        Instant now = clock.instant();
        repository.revokeById(current.id(), now);
        return insertNewToken(current.connectionId(), current.scopesJson(), now);
    }

    /** 연동 해제용. 없거나 이미 폐기된 토큰이면 아무 일도 하지 않는다(멱등). */
    @Transactional
    public void revokeAllForConnection(long connectionId) {
        repository.revokeAllActiveByConnectionId(connectionId, clock.instant());
    }

    private IssuedAgentRefreshToken insertNewToken(long connectionId, String scopesJson, Instant now) {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String plainToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant expiresAt = now.plus(REFRESH_TOKEN_TTL);

        repository.insert(connectionId, sha256Hex(plainToken), scopesJson, expiresAt, now);
        return new IssuedAgentRefreshToken(plainToken, expiresAt);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }
}
