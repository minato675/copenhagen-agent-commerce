package com.copenhagen.core.auth.login;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.common.observability.CoreLogContext;

@RestController
@RequestMapping("/api/core/v1/auth/login")
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final AuthLoginService authLoginService;

    public LoginController(AuthLoginService authLoginService) {
        this.authLoginService = authLoginService;
    }

    @PostMapping
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        long startedAt = System.nanoTime();
        String loginIdHash = CoreLogContext.fingerprint(request.loginId());
        String deviceIdHash = CoreLogContext.fingerprint(request.deviceId());
        log.info("core.login.start loginIdHash={} deviceIdHash={}", loginIdHash, deviceIdHash);
        try {
            AuthLoginOutcome outcome = authLoginService.login(
                    request.loginId(), request.password(), request.deviceId(), request.deviceName());
            CoreLogContext.putFingerprint(
                    CoreLogContext.USER_ID,
                    outcome.loginResult().userId().toString());
            log.info(
                    "core.login.success userIdHash={} durationMs={}",
                    CoreLogContext.get(CoreLogContext.USER_ID),
                    elapsedMillis(startedAt));
            return ResponseEntity.ok(LoginResponse.from(outcome));
        } catch (CoreException exception) {
            log.warn(
                    "core.login.failed loginIdHash={} errorCode={} durationMs={}",
                    loginIdHash,
                    exception.errorCode().code(),
                    elapsedMillis(startedAt));
            throw exception;
        } catch (RuntimeException exception) {
            log.error(
                    "core.login.failed loginIdHash={} exceptionType={} durationMs={}",
                    loginIdHash,
                    exception.getClass().getSimpleName(),
                    elapsedMillis(startedAt));
            throw exception;
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record LoginRequest(
            @NotBlank
            String loginId,

            @NotBlank
            String password,

            @NotBlank
            String deviceId,

            @NotBlank
            String deviceName) {

        @Override
        public String toString() {
            return "LoginRequest[loginId='" + loginId + "', password=<redacted>, deviceId='" + deviceId
                    + "', deviceName='" + deviceName + "']";
        }
    }

    public record LoginResponse(
            String accessToken,
            String refreshToken,
            long expiresIn,
            UserSummary user) {

        static LoginResponse from(AuthLoginOutcome outcome) {
            return new LoginResponse(
                    outcome.accessToken(),
                    outcome.refreshToken(),
                    outcome.expiresInSeconds(),
                    UserSummary.from(outcome.loginResult()));
        }

        @Override
        public String toString() {
            return "LoginResponse[accessToken=<redacted>, refreshToken=<redacted>, expiresIn=" + expiresIn
                    + ", user=" + user + ']';
        }

        public record UserSummary(UUID userId, String loginId, String name) {

            static UserSummary from(LoginResult result) {
                return new UserSummary(result.userId(), result.loginId(), result.name());
            }
        }
    }
}
