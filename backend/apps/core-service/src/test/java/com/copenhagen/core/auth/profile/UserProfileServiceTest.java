package com.copenhagen.core.auth.profile;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER_ID = 42L;
    private static final UUID PUBLIC_ID = UUID.randomUUID();
    private static final String EMAIL = "user@example.com";

    /** 테스트 전용 고정 키. 실제 암호화·복호화를 그대로 통과시켜 왕복을 검증한다. */
    private static final PiiEncryptor PII_ENCRYPTOR =
            new PiiEncryptor(Base64.getEncoder().encodeToString(new byte[32]));

    private UserRepository userRepository;
    private LocalAccountRepository localAccountRepository;
    private UserProfileService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        localAccountRepository = mock(LocalAccountRepository.class);
        service = new UserProfileService(userRepository, localAccountRepository, PII_ENCRYPTOR);
    }

    @Test
    @DisplayName("정상 조회는 userId/loginId/name과 복호화한 email을 반환한다")
    void getProfile_existingUser_returnsSummaryWithEmail() {
        when(userRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(user()));
        when(localAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(localAccount()));

        UserProfileResult result = service.getProfile(PUBLIC_ID);

        assertEquals(PUBLIC_ID, result.userId());
        assertEquals("login-id", result.loginId());
        assertEquals("홍길동", result.name());
        assertEquals(EMAIL, result.email());
    }

    @Test
    @DisplayName("암호문이 손상됐으면 빈 값을 주지 않고 실패한다")
    void getProfile_corruptedEmail_throws() {
        User corrupted = new User(
                USER_ID, PUBLIC_ID, "홍길동", UserStatus.ACTIVE,
                EncryptedPii.of(new byte[] {1, 2, 3}), "email-hash", true,
                null, null, null, null, null, NOW, NOW);
        when(userRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(corrupted));
        when(localAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(localAccount()));

        assertThrows(IllegalStateException.class, () -> service.getProfile(PUBLIC_ID));
    }

    @Test
    @DisplayName("user가 없으면 AUTH_TOKEN_INVALID를 던진다")
    void getProfile_missingUser_throwsInvalid() {
        when(userRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.empty());

        CoreException exception = assertThrows(CoreException.class, () -> service.getProfile(PUBLIC_ID));

        assertEquals(CoreErrorCode.AUTH_TOKEN_INVALID, exception.errorCode());
    }

    @Test
    @DisplayName("local_account가 없으면 AUTH_TOKEN_INVALID를 던진다")
    void getProfile_missingLocalAccount_throwsInvalid() {
        when(userRepository.findByPublicId(PUBLIC_ID)).thenReturn(Optional.of(user()));
        when(localAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        CoreException exception = assertThrows(CoreException.class, () -> service.getProfile(PUBLIC_ID));

        assertEquals(CoreErrorCode.AUTH_TOKEN_INVALID, exception.errorCode());
    }

    private static User user() {
        return new User(
                USER_ID, PUBLIC_ID, "홍길동", UserStatus.ACTIVE,
                PII_ENCRYPTOR.encrypt(EMAIL), "email-hash", true,
                null, null, null, null, null, NOW, NOW);
    }

    private static LocalAccount localAccount() {
        return new LocalAccount(
                7L, USER_ID, "login-id", "password-hash", 0, null, null, NOW, NOW);
    }
}
