package com.copenhagen.core.address;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.copenhagen.core.common.crypto.EncryptedPii;

public interface AddressRepository {

    /** Soft delete되지 않은 배송지만 센다(등록 20개 제한 기준). */
    int countActiveByUserId(long userId);

    long insert(
            UUID publicId,
            long userId,
            boolean isDefault,
            EncryptedPii recipientNameEncrypted,
            EncryptedPii recipientPhoneEncrypted,
            EncryptedPii postalCodeEncrypted,
            EncryptedPii addressLine1Encrypted,
            EncryptedPii addressLine2Encrypted,
            EncryptedPii deliveryRequestEncrypted,
            Instant now);

    /** 기본 배송지 우선, 그다음 최신 등록순으로 정렬해 돌려준다. */
    List<Address> findAllActiveByUserId(long userId);

    /** 소유권 검사를 쿼리 조건에 내장한다 — 다른 사용자의 배송지는 빈 Optional로 취급한다. */
    Optional<Address> findActiveByPublicIdAndUserId(UUID publicId, long userId);

    void update(
            long id,
            EncryptedPii recipientNameEncrypted,
            EncryptedPii recipientPhoneEncrypted,
            EncryptedPii postalCodeEncrypted,
            EncryptedPii addressLine1Encrypted,
            EncryptedPii addressLine2Encrypted,
            EncryptedPii deliveryRequestEncrypted,
            Instant now);

    void softDelete(long id, Instant now);

    /** 해당 사용자의 활성 기본 배송지를 모두 해제한다(정상 상태에서는 최대 1건). */
    void clearDefault(long userId, Instant now);

    void setDefault(long id, Instant now);
}
