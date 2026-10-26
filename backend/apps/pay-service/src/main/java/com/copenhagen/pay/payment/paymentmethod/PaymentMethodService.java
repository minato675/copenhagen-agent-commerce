package com.copenhagen.pay.payment.paymentmethod;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentMethodService {

    private final PaymentMethodRepository repository;

    public PaymentMethodService(PaymentMethodRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PaymentMethod register(
            String userId,
            String provider,
            String providerTokenRef,
            String brand,
            String last4,
            Integer expiryMonth,
            Integer expiryYear) {
        requireNonBlank(provider, "provider");
        requireNonBlank(providerTokenRef, "providerTokenRef");
        if (provider.length() > 50
                || providerTokenRef.length() > 255
                || brand != null && brand.length() > 30
                || last4 != null && !last4.matches("\\d{4}")
                || expiryMonth != null && (expiryMonth < 1 || expiryMonth > 12)
                || expiryYear != null && (expiryYear < 0 || expiryYear > 9999)) {
            throw new PayException(PayErrorCode.INVALID_REQUEST);
        }

        boolean defaultMethod = repository.findActiveByUserId(userId).isEmpty();
        PaymentMethod paymentMethod = new PaymentMethod(
                UUID.randomUUID().toString(),
                userId,
                provider,
                providerTokenRef,
                brand,
                last4,
                expiryMonth,
                expiryYear,
                PaymentMethodStatus.ACTIVE,
                defaultMethod);
        try {
            repository.insert(paymentMethod);
        } catch (DuplicateKeyException exception) {
            throw new PayException(
                    PayErrorCode.PAYMENT_METHOD_ALREADY_REGISTERED,
                    PayErrorCode.PAYMENT_METHOD_ALREADY_REGISTERED.defaultMessage(),
                    null,
                    PayErrorCode.PAYMENT_METHOD_ALREADY_REGISTERED.defaultNextAction(),
                    exception);
        }
        return paymentMethod;
    }

    @Transactional(readOnly = true)
    public List<PaymentMethod> findActive(String userId) {
        return repository.findActiveByUserId(userId);
    }

    @Transactional
    public PaymentMethod markDefault(String userId, String paymentMethodId) {
        PaymentMethod paymentMethod = findOwnedActive(userId, paymentMethodId);
        if (!paymentMethod.defaultMethod()) {
            repository.clearDefaultForUser(userId);
            int updated = repository.markDefault(paymentMethodId, userId);
            if (updated != 1) {
                throw new PayException(PayErrorCode.PAYMENT_METHOD_INACTIVE);
            }
        }
        return new PaymentMethod(
                paymentMethod.id(),
                paymentMethod.userId(),
                paymentMethod.provider(),
                paymentMethod.providerTokenRef(),
                paymentMethod.brand(),
                paymentMethod.last4(),
                paymentMethod.expiryMonth(),
                paymentMethod.expiryYear(),
                paymentMethod.status(),
                true);
    }

    @Transactional
    public void delete(String userId, String paymentMethodId) {
        findOwnedActive(userId, paymentMethodId);
        int updated = repository.softDelete(paymentMethodId, userId);
        if (updated != 1) {
            throw new PayException(PayErrorCode.PAYMENT_METHOD_INACTIVE);
        }
    }

    private PaymentMethod findOwnedActive(String userId, String paymentMethodId) {
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            throw new PayException(PayErrorCode.PAYMENT_METHOD_NOT_FOUND);
        }
        return repository.findActiveByIdAndUserIdForUpdate(paymentMethodId, userId)
                .orElseThrow(() -> new PayException(PayErrorCode.PAYMENT_METHOD_NOT_FOUND));
    }

    private void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PayException(
                    PayErrorCode.INVALID_REQUEST,
                    fieldName + "은(는) 필수입니다.",
                    null,
                    "CHECK_REQUEST");
        }
    }
}
