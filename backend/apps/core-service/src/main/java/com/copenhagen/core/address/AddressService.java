package com.copenhagen.core.address;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

@Service
public class AddressService {

    /** 사용자당 등록 가능한 배송지 최대 개수(Notion API 명세 "배송지 등록" 비고). */
    private static final int MAX_ADDRESSES_PER_USER = 20;

    private final AddressRepository addressRepository;
    private final PiiEncryptor piiEncryptor;
    private final Clock clock;

    public AddressService(AddressRepository addressRepository, PiiEncryptor piiEncryptor, Clock clock) {
        this.addressRepository = addressRepository;
        this.piiEncryptor = piiEncryptor;
        this.clock = clock;
    }

    @Transactional
    public UUID register(
            long userId,
            String recipientName,
            String recipientPhone,
            String postalCode,
            String addressLine1,
            String addressLine2,
            String deliveryRequest) {
        int activeCount = addressRepository.countActiveByUserId(userId);
        if (activeCount >= MAX_ADDRESSES_PER_USER) {
            throw new CoreException(CoreErrorCode.ADDRESS_LIMIT_EXCEEDED);
        }
        boolean isDefault = activeCount == 0;
        UUID publicId = UUID.randomUUID();
        Instant now = clock.instant();
        addressRepository.insert(
                publicId,
                userId,
                isDefault,
                piiEncryptor.encrypt(recipientName),
                piiEncryptor.encrypt(recipientPhone),
                piiEncryptor.encrypt(postalCode),
                piiEncryptor.encrypt(addressLine1),
                piiEncryptor.encrypt(addressLine2),
                piiEncryptor.encrypt(deliveryRequest),
                now);
        return publicId;
    }

    public List<AddressResult> list(long userId) {
        return addressRepository.findAllActiveByUserId(userId).stream()
                .map(this::toResult)
                .toList();
    }

    public AddressResult get(long userId, UUID addressId) {
        Address address = addressRepository.findActiveByPublicIdAndUserId(addressId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.ADDRESS_NOT_FOUND));
        return toResult(address);
    }

    @Transactional
    public AddressResult update(
            long userId,
            UUID addressId,
            String recipientName,
            String recipientPhone,
            String postalCode,
            String addressLine1,
            String addressLine2,
            String deliveryRequest) {
        Address existing = addressRepository.findActiveByPublicIdAndUserId(addressId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.ADDRESS_NOT_FOUND));
        Instant now = clock.instant();
        addressRepository.update(
                existing.id(),
                piiEncryptor.encrypt(recipientName),
                piiEncryptor.encrypt(recipientPhone),
                piiEncryptor.encrypt(postalCode),
                piiEncryptor.encrypt(addressLine1),
                piiEncryptor.encrypt(addressLine2),
                piiEncryptor.encrypt(deliveryRequest),
                now);
        return new AddressResult(
                addressId, recipientName, recipientPhone, postalCode,
                addressLine1, addressLine2, deliveryRequest, existing.isDefault());
    }

    @Transactional
    public void delete(long userId, UUID addressId) {
        Address existing = addressRepository.findActiveByPublicIdAndUserId(addressId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.ADDRESS_NOT_FOUND));
        Instant now = clock.instant();
        addressRepository.softDelete(existing.id(), now);
        if (existing.isDefault()) {
            promoteNextDefault(userId, now);
        }
    }

    @Transactional
    public void setDefault(long userId, UUID addressId) {
        Address existing = addressRepository.findActiveByPublicIdAndUserId(addressId, userId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.ADDRESS_NOT_FOUND));
        if (existing.isDefault()) {
            return;
        }
        Instant now = clock.instant();
        addressRepository.clearDefault(userId, now);
        addressRepository.setDefault(existing.id(), now);
    }

    /** 남은 배송지 중 최신 등록순 1건을 새 기본 배송지로 승격한다. 남은 배송지가 없으면 그대로 둔다. */
    private void promoteNextDefault(long userId, Instant now) {
        List<Address> remaining = addressRepository.findAllActiveByUserId(userId);
        if (!remaining.isEmpty()) {
            addressRepository.setDefault(remaining.get(0).id(), now);
        }
    }

    private AddressResult toResult(Address address) {
        return new AddressResult(
                address.publicId(),
                piiEncryptor.decrypt(address.recipientNameEncrypted()),
                piiEncryptor.decrypt(address.recipientPhoneEncrypted()),
                piiEncryptor.decrypt(address.postalCodeEncrypted()),
                piiEncryptor.decrypt(address.addressLine1Encrypted()),
                piiEncryptor.decrypt(address.addressLine2Encrypted()),
                piiEncryptor.decrypt(address.deliveryRequestEncrypted()),
                address.isDefault());
    }
}
