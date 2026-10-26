package com.copenhagen.core.auth.profile;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.copenhagen.core.auth.localaccount.LocalAccount;
import com.copenhagen.core.auth.localaccount.LocalAccountRepository;
import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final LocalAccountRepository localAccountRepository;
    private final PiiEncryptor piiEncryptor;

    public UserProfileService(
            UserRepository userRepository,
            LocalAccountRepository localAccountRepository,
            PiiEncryptor piiEncryptor) {
        this.userRepository = userRepository;
        this.localAccountRepository = localAccountRepository;
        this.piiEncryptor = piiEncryptor;
    }

    /**
     * 이메일은 {@code users.email_encrypted}(NOT NULL)를 복호화해 돌려준다. 가입 시
     * {@code PiiNormalizer.email}로 정규화된 평문이 저장되므로 그대로 반환하면 된다.
     * 키가 다르거나 암호문이 손상됐다면 빈 값을 주지 않고 {@code PiiEncryptor}가 그대로 실패시킨다.
     */
    public UserProfileResult getProfile(UUID userPublicId) {
        User user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID));
        LocalAccount localAccount = localAccountRepository.findByUserId(user.id())
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID));
        return new UserProfileResult(
                user.publicId(),
                localAccount.loginId(),
                user.name(),
                piiEncryptor.decrypt(user.emailEncrypted()));
    }
}
