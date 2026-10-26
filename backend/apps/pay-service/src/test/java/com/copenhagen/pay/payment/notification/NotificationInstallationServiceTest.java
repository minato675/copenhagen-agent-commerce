package com.copenhagen.pay.payment.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationInstallationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    @Test
    void registersSameUserAndFidWithoutCreatingDuplicateBinding() {
        InMemoryRepository repository = new InMemoryRepository();
        NotificationInstallationService service = new NotificationInstallationService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.register("user-1", "fid-1");
        service.register("user-1", "fid-1");

        assertEquals(List.of("fid-1"), repository.activeFids("user-1"));
        assertEquals(2, repository.registerCount);
    }

    @Test
    void rebindsFidToTheLatestUser() {
        InMemoryRepository repository = new InMemoryRepository();
        NotificationInstallationService service = new NotificationInstallationService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.register("user-1", "fid-1");
        service.register("user-2", "fid-1");

        assertTrue(repository.activeFids("user-1").isEmpty());
        assertEquals(List.of("fid-1"), repository.activeFids("user-2"));
    }

    @Test
    void unregisterIsIdempotentAndScopedToTheCurrentUser() {
        InMemoryRepository repository = new InMemoryRepository();
        NotificationInstallationService service = new NotificationInstallationService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.register("user-1", "fid-1");
        PayException exception = assertThrows(
                PayException.class,
                () -> service.unregister("user-2", "fid-1"));
        assertEquals(PayErrorCode.RESOURCE_NOT_FOUND, exception.errorCode());
        assertEquals(List.of("fid-1"), repository.activeFids("user-1"));

        service.unregister("user-1", "fid-1");
        service.unregister("user-1", "fid-1");
        assertTrue(repository.activeFids("user-1").isEmpty());
    }

    private static final class InMemoryRepository implements NotificationInstallationRepository {

        private final List<Binding> bindings = new ArrayList<>();
        private int registerCount;

        @Override
        public void registerActive(String userId, String fid, Instant now) {
            bindings.stream()
                    .filter(binding -> binding.fid.equals(fid) && !binding.userId.equals(userId))
                    .forEach(binding -> binding.active = false);
            Binding existing = bindings.stream()
                    .filter(binding -> binding.userId.equals(userId) && binding.fid.equals(fid))
                    .findFirst()
                    .orElseGet(() -> {
                        Binding created = new Binding(userId, fid);
                        bindings.add(created);
                        return created;
                    });
            existing.active = true;
            registerCount++;
        }

        @Override
        public int deactivate(String userId, String fid, Instant now) {
            return bindings.stream()
                    .filter(binding -> binding.userId.equals(userId)
                            && binding.fid.equals(fid)
                            && binding.active)
                    .peek(binding -> binding.active = false)
                    .mapToInt(binding -> 1)
                    .sum();
        }

        @Override
        public boolean existsActiveForAnotherUser(String userId, String fid) {
            return bindings.stream()
                    .anyMatch(binding -> binding.fid.equals(fid)
                            && !binding.userId.equals(userId)
                            && binding.active);
        }

        @Override
        public List<String> findActiveFidsByUserId(String userId) {
            return activeFids(userId);
        }

        private List<String> activeFids(String userId) {
            return bindings.stream()
                    .filter(binding -> binding.userId.equals(userId) && binding.active)
                    .map(binding -> binding.fid)
                    .toList();
        }

        private static final class Binding {

            private final String userId;
            private final String fid;
            private boolean active = true;

            private Binding(String userId, String fid) {
                this.userId = userId;
                this.fid = fid;
            }
        }
    }
}
