package com.copenhagen.core.auth.refreshtoken;

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

/** 로그인 성공 시 Refresh Token을 발급하고, 갱신(Rotation)·로그아웃 폐기를 처리한다. 정책은 §11.2.1·§11.2.5 확정 사항 그대로다. */
@Service
public class RefreshTokenService {

    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final RefreshTokenBreachService breachService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            RefreshTokenRepository repository,
            RefreshTokenBreachService breachService,
            Clock clock) {
        this.repository = repository;
        this.breachService = breachService;
        this.clock = clock;
    }

    @Transactional
    public IssuedRefreshToken issue(long userId, String deviceId, String deviceName) {
        Instant now = clock.instant();
        repository.revokeActiveByUserIdAndDeviceId(userId, deviceId, now);
        return insertNewToken(userId, deviceId, deviceName, now);
    }

    /**
     * 회전 대상 행을 잠근 채로 검증해 반환한다. 계정 상태 검사는 호출자가 이어서 수행한다 — 상태가 막힌 계정의
     * 토큰까지 미리 폐기하지 않기 위해 검증과 회전을 분리했다.
     *
     * <p>만료는 탈취가 아니므로 전체 폐기 없이 {@code AUTH_REFRESH_TOKEN_INVALID}로만 거부한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefreshToken verifyForRotation(String plainToken) {
        RefreshToken current = repository.findByTokenHashForUpdate(sha256Hex(plainToken))
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        if (current.revokedAt() != null) {
            breachService.revokeAllForUser(current.userId());
            throw new CoreException(CoreErrorCode.AUTH_REFRESH_TOKEN_REUSED);
        }
        if (!current.expiresAt().isAfter(clock.instant())) {
            throw new CoreException(CoreErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }
        return current;
    }

    /**
     * 잠근 행을 폐기하고 같은 기기로 새 토큰을 발급한다. {@code device_id}·{@code device_name}은 기존 행에서
     * 그대로 승계한다 — 클라이언트는 갱신 요청에 기기 정보를 보내지 않는다(API 명세).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedRefreshToken rotate(RefreshToken current) {
        Instant now = clock.instant();
        repository.revokeById(current.id(), now);
        return insertNewToken(current.userId(), current.deviceId(), current.deviceName(), now);
    }

    /** 로그아웃용. 없거나 이미 폐기된 토큰이면 아무 일도 하지 않는다(멱등). */
    @Transactional
    public void revoke(String plainToken) {
        repository.findByTokenHash(sha256Hex(plainToken))
                .filter(token -> token.revokedAt() == null)
                .ifPresent(token -> repository.revokeById(token.id(), clock.instant()));
    }

    private IssuedRefreshToken insertNewToken(long userId, String deviceId, String deviceName, Instant now) {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String plainToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        Instant expiresAt = now.plus(REFRESH_TOKEN_TTL);

        repository.insert(userId, sha256Hex(plainToken), deviceId, deviceName, expiresAt, now);
        return new IssuedRefreshToken(plainToken, expiresAt);
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
