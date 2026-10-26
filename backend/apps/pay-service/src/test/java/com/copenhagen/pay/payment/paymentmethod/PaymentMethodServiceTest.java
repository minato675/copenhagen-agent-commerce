package com.copenhagen.pay.payment.paymentmethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaymentMethodServiceTest {

    @Test
    void firstCardIsDefaultAndSecondCardIsNotDefault() {
        InMemoryPaymentMethodRepository repository = new InMemoryPaymentMethodRepository();
        PaymentMethodService service = new PaymentMethodService(repository);

        PaymentMethod first = register(service, "token-001");
        PaymentMethod second = register(service, "token-002");

        assertTrue(first.defaultMethod());
        assertFalse(second.defaultMethod());
        assertEquals(1, repository.findActiveByUserId("user-001").stream()
                .filter(PaymentMethod::defaultMethod)
                .count());
    }

    @Test
    void markingCardDefaultClearsPreviousDefault() {
        InMemoryPaymentMethodRepository repository = new InMemoryPaymentMethodRepository();
        PaymentMethodService service = new PaymentMethodService(repository);

        PaymentMethod first = register(service, "token-001");
        PaymentMethod second = register(service, "token-002");

        PaymentMethod changed = service.markDefault("user-001", second.id());

        assertTrue(changed.defaultMethod());
        assertEquals(second.id(), repository.findActiveByUserId("user-001").stream()
                .filter(PaymentMethod::defaultMethod)
                .findFirst()
                .orElseThrow()
                .id());
        assertFalse(repository.findActiveByIdAndUserIdForUpdate(first.id(), "user-001")
                .orElseThrow()
                .defaultMethod());
    }

    @Test
    void deleteSoftDeletesCardAndRemovesItFromActiveList() {
        InMemoryPaymentMethodRepository repository = new InMemoryPaymentMethodRepository();
        PaymentMethodService service = new PaymentMethodService(repository);
        PaymentMethod paymentMethod = register(service, "token-001");

        service.delete("user-001", paymentMethod.id());

        assertTrue(service.findActive("user-001").isEmpty());
        assertEquals(
                PaymentMethodStatus.REVOKED,
                repository.all().get(paymentMethod.id()).status());
        assertFalse(repository.all().get(paymentMethod.id()).defaultMethod());
    }

    private PaymentMethod register(PaymentMethodService service, String tokenReference) {
        return service.register(
                "user-001",
                "PG_MOCK",
                tokenReference,
                "VISA",
                "1234",
                12,
                2030);
    }

    private static final class InMemoryPaymentMethodRepository
            implements PaymentMethodRepository {

        private final Map<String, PaymentMethod> paymentMethods = new HashMap<>();

        @Override
        public List<PaymentMethod> findActiveByUserId(String userId) {
            return paymentMethods.values().stream()
                    .filter(paymentMethod -> paymentMethod.userId().equals(userId))
                    .filter(paymentMethod -> paymentMethod.status() == PaymentMethodStatus.ACTIVE)
                    .toList();
        }

        @Override
        public Optional<PaymentMethod> findActiveByIdAndUserIdForUpdate(
                String paymentMethodId,
                String userId) {
            return Optional.ofNullable(paymentMethods.get(paymentMethodId))
                    .filter(paymentMethod -> paymentMethod.userId().equals(userId))
                    .filter(paymentMethod -> paymentMethod.status() == PaymentMethodStatus.ACTIVE);
        }

        @Override
        public void insert(PaymentMethod paymentMethod) {
            paymentMethods.put(paymentMethod.id(), paymentMethod);
        }

        @Override
        public int clearDefaultForUser(String userId) {
            List<PaymentMethod> activeMethods = new ArrayList<>(findActiveByUserId(userId));
            activeMethods.forEach(paymentMethod -> paymentMethods.put(
                    paymentMethod.id(),
                    withDefault(paymentMethod, false)));
            return activeMethods.size();
        }

        @Override
        public int markDefault(String paymentMethodId, String userId) {
            PaymentMethod paymentMethod = paymentMethods.get(paymentMethodId);
            if (paymentMethod == null || !paymentMethod.userId().equals(userId)) {
                return 0;
            }
            paymentMethods.put(paymentMethodId, withDefault(paymentMethod, true));
            return 1;
        }

        @Override
        public int softDelete(String paymentMethodId, String userId) {
            PaymentMethod paymentMethod = paymentMethods.get(paymentMethodId);
            if (paymentMethod == null || !paymentMethod.userId().equals(userId)) {
                return 0;
            }
            paymentMethods.put(
                    paymentMethodId,
                    new PaymentMethod(
                            paymentMethod.id(),
                            paymentMethod.userId(),
                            paymentMethod.provider(),
                            paymentMethod.providerTokenRef(),
                            paymentMethod.brand(),
                            paymentMethod.last4(),
                            paymentMethod.expiryMonth(),
                            paymentMethod.expiryYear(),
                            PaymentMethodStatus.REVOKED,
                            false));
            return 1;
        }

        private PaymentMethod withDefault(PaymentMethod paymentMethod, boolean defaultMethod) {
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
                    defaultMethod);
        }

        private Map<String, PaymentMethod> all() {
            return paymentMethods;
        }
    }
}
