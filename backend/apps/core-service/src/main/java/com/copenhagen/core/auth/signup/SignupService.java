package com.copenhagen.core.auth.signup;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.crypto.PiiHasher;
import com.copenhagen.core.common.crypto.PiiNormalizer;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

@Service
public class SignupService {

    private static final int NAME_MAX_LENGTH = 20;
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int PASSWORD_MAX_LENGTH = 20;
    private static final char ASCII_PRINTABLE_MIN = 0x21;
    private static final char ASCII_PRINTABLE_MAX = 0x7E;

    private final UserRepository userRepository;
    private final LocalAccountRepository localAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final PiiEncryptor piiEncryptor;
    private final PiiHasher piiHasher;
    private final Clock clock;

    public SignupService(
            UserRepository userRepository,
            LocalAccountRepository localAccountRepository,
            PasswordEncoder passwordEncoder,
            PiiEncryptor piiEncryptor,
            PiiHasher piiHasher,
            Clock clock) {
        this.userRepository = userRepository;
        this.localAccountRepository = localAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.piiEncryptor = piiEncryptor;
        this.piiHasher = piiHasher;
        this.clock = clock;
    }

    @Transactional
    public SignupResult signup(String loginId, String password, String email, String name) {
        validatePassword(password, loginId);
        String normalizedName = normalizeName(name);
        String normalizedEmail = PiiNormalizer.email(email);
        String emailHash = piiHasher.hashEmail(normalizedEmail);

        if (localAccountRepository.existsByLoginId(loginId)) {
            throw new CoreException(CoreErrorCode.AUTH_LOGIN_ID_DUPLICATED);
        }
        if (userRepository.existsByEmailHash(emailHash)) {
            throw new CoreException(CoreErrorCode.AUTH_EMAIL_DUPLICATED);
        }

        EncryptedPii emailEncrypted = piiEncryptor.encrypt(normalizedEmail);
        String passwordHash = passwordEncoder.encode(password);
        Instant now = clock.instant();
        UUID publicId = UUID.randomUUID();

        try {
            long userId = userRepository.insert(
                    publicId, normalizedName, UserStatus.ACTIVE, emailEncrypted, emailHash, now);
            localAccountRepository.insert(userId, loginId, passwordHash, now);
            return new SignupResult(publicId, loginId, normalizedEmail, normalizedName);
        } catch (DuplicateKeyException exception) {
            throw translateDuplicateKey(exception);
        }
    }

    private CoreException translateDuplicateKey(DuplicateKeyException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("email_hash")) {
            return new CoreException(CoreErrorCode.AUTH_EMAIL_DUPLICATED);
        }
        if (message.contains("login_id")) {
            return new CoreException(CoreErrorCode.AUTH_LOGIN_ID_DUPLICATED);
        }
        return new CoreException(
                CoreErrorCode.INTERNAL_ERROR,
                CoreErrorCode.INTERNAL_ERROR.defaultMessage(),
                null,
                CoreErrorCode.INTERNAL_ERROR.defaultNextAction(),
                exception);
    }

    /** 실패 조건을 순서대로 확인하고 먼저 걸리는 규칙의 메시지로 실패시킨다. */
    private void validatePassword(String password, String loginId) {
        if (password.length() < PASSWORD_MIN_LENGTH || password.length() > PASSWORD_MAX_LENGTH) {
            throw invalidPassword("비밀번호는 8~20자여야 합니다.");
        }
        if (!isAsciiPrintableOnly(password)) {
            throw invalidPassword("비밀번호에 공백이나 허용되지 않는 문자를 포함할 수 없습니다.");
        }
        if (countCharacterClasses(password) < 2) {
            throw invalidPassword("비밀번호는 영문·숫자·특수문자 중 2종 이상을 포함해야 합니다.");
        }
        if (password.equalsIgnoreCase(loginId)) {
            throw invalidPassword("비밀번호는 아이디와 동일할 수 없습니다.");
        }
        if (containsRepeatingOrSequentialRun(password)) {
            throw invalidPassword("비밀번호에 단순 반복 또는 연속된 문자열을 포함할 수 없습니다.");
        }
    }

    private static CoreException invalidPassword(String message) {
        return new CoreException(CoreErrorCode.INVALID_REQUEST, message, null, "CHECK_REQUEST");
    }

    private static boolean isAsciiPrintableOnly(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < ASCII_PRINTABLE_MIN || c > ASCII_PRINTABLE_MAX) {
                return false;
            }
        }
        return true;
    }

    private static int countCharacterClasses(String value) {
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }
        int classes = 0;
        if (hasLetter) {
            classes++;
        }
        if (hasDigit) {
            classes++;
        }
        if (hasSpecial) {
            classes++;
        }
        return classes;
    }

    /** ASCII 코드값 기준 3글자 슬라이딩 윈도우: 동일 반복 / 오름차순 / 내림차순. 키보드 배열은 고려하지 않는다. */
    private static boolean containsRepeatingOrSequentialRun(String value) {
        for (int i = 0; i + 2 < value.length(); i++) {
            char a = value.charAt(i);
            char b = value.charAt(i + 1);
            char c = value.charAt(i + 2);
            boolean repeating = a == b && b == c;
            boolean ascending = (b - a == 1) && (c - b == 1);
            boolean descending = (a - b == 1) && (b - c == 1);
            if (repeating || ascending || descending) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeName(String name) {
        String trimmed = name.trim();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new CoreException(
                    CoreErrorCode.INVALID_REQUEST, "이름은 20자 이하여야 합니다.", null, "CHECK_REQUEST");
        }
        return trimmed;
    }
}
