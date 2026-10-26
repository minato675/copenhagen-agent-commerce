package com.copenhagen.core.auth.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.copenhagen.core.common.crypto.EncryptedPii;

public interface UserRepository {

    /**
     * 이메일은 평문이 아니라 암호문과 조회용 해시로 받는다.
     * 호출자가 {@code PiiEncryptor}/{@code PiiHasher}로 변환한 뒤 넘긴다.
     */
    long insert(UUID publicId, String name, UserStatus status,
            EncryptedPii emailEncrypted, String emailHash, Instant now);

    /** {@code uq_users_email_hash_active}(WHERE deleted_at IS NULL) 기준 활성 사용자만 대상으로 한다. */
    boolean existsByEmailHash(String emailHash);

    Optional<User> findById(long id);

    Optional<User> findByPublicId(UUID publicId);

    /** 토큰 발급까지 성공한 로그인만 호출한다(INIT-02.3, "최종 로그인 성공 기준"). */
    void updateLastLoginAt(long id, Instant now);
}
