package com.copenhagen.pay.payment.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.idempotency.IdempotencyGuard;
import com.copenhagen.pay.payment.merchant.MerchantPublicKeyClient;
import com.copenhagen.pay.payment.merchant.MerchantServiceProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.copenhagen.pay.payment.merchant.MerchantAccount;
import com.copenhagen.pay.payment.merchant.MerchantAccountRepository;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethod;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethodRepository;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethodStatus;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;

class AuthorizationSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    private static final String KEY_ID = "merchant-a-es256-1";
    private ECKey signingKey;

    @BeforeEach
    void setUp() throws JOSEException {
        signingKey = new ECKeyGenerator(Curve.P_256)
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.ES256)
                .generate();
    }

    @Test
    void createsPaymentMethodRequiredSessionWhenUserHasNoActiveCard() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        AuthorizationSessionService service = service(store, new InMemoryPaymentMethodRepository());

        AuthorizationSessionService.CreateResult result = service.create(
                command(),
                "request-001",
                "hash-001");

        assertEquals(AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED, result.session().status());
        assertNull(result.session().selectedPaymentMethodId());
        assertEquals(
                "러닝화",
                service.displaySnapshot(result.session().items().getFirst())
                        .summary().getFirst().name());
        assertEquals(NOW.plusSeconds(300), result.session().expiresAt());
    }

    @Test
    void createsPendingSessionWithTheUsersDefaultCard() {
        InMemoryPaymentMethodRepository paymentMethods = new InMemoryPaymentMethodRepository();
        paymentMethods.add(new PaymentMethod(
                "method-001",
                "user-001",
                "PG_MOCK",
                "token-001",
                "VISA",
                "1234",
                12,
                2030,
                PaymentMethodStatus.ACTIVE,
                true));
        AuthorizationSessionService service = service(
                new InMemoryAuthorizationSessionStore(),
                paymentMethods);

        AuthorizationSession session = service.create(command(), "request-001", "hash-001")
                .session();

        assertEquals(AuthorizationSessionStatus.PENDING, session.status());
        assertEquals("method-001", session.selectedPaymentMethodId());
    }

    @Test
    void selectsCardAndResumesPaymentMethodRequiredSession() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        InMemoryPaymentMethodRepository paymentMethods = new InMemoryPaymentMethodRepository();
        AuthorizationSessionService service = service(store, paymentMethods);
        AuthorizationSession before = service.create(command(), "request-001", "hash-001")
                .session();
        paymentMethods.add(paymentMethod("method-002", "user-001", true));

        AuthorizationSession after = service.selectPaymentMethod(
                "user-001",
                before.id(),
                "method-002");

        assertEquals(AuthorizationSessionStatus.PENDING, after.status());
        assertEquals("method-002", after.selectedPaymentMethodId());
        assertEquals(
                before.items().getFirst().checkoutDigest(),
                after.items().getFirst().checkoutDigest());
        assertEquals(
                before.items().getFirst().displaySnapshotJson(),
                after.items().getFirst().displaySnapshotJson());
        assertEquals(before.totalAmountMinor(), after.totalAmountMinor());
        assertEquals(before.currency(), after.currency());
        assertEquals(before.expiresAt(), after.expiresAt());
        assertEquals(1, store.sessions.size());
        assertEquals(before.version() + 1, after.version());
    }

    @Test
    void replacesOnlyCardOnPendingSession() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        InMemoryPaymentMethodRepository paymentMethods = new InMemoryPaymentMethodRepository();
        paymentMethods.add(paymentMethod("method-001", "user-001", true));
        paymentMethods.add(paymentMethod("method-002", "user-001", false));
        AuthorizationSessionService service = service(store, paymentMethods);
        AuthorizationSession before = service.create(command(), "request-001", "hash-001")
                .session();

        AuthorizationSession after = service.selectPaymentMethod(
                "user-001",
                before.id(),
                "method-002");

        assertEquals(AuthorizationSessionStatus.PENDING, after.status());
        assertEquals("method-002", after.selectedPaymentMethodId());
        assertEquals(before.requiredAction(), after.requiredAction());
        assertEquals(before.purchaseRequestId(), after.purchaseRequestId());
        assertEquals(
                before.items().getFirst().externalCheckoutId(),
                after.items().getFirst().externalCheckoutId());
        assertEquals(before.expiresAt(), after.expiresAt());
    }

    @Test
    void cannotSelectCardForAnotherUsersSession() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        InMemoryPaymentMethodRepository paymentMethods = new InMemoryPaymentMethodRepository();
        paymentMethods.add(paymentMethod("method-001", "user-001", true));
        AuthorizationSessionService service = service(store, paymentMethods);
        AuthorizationSession session = service.create(command(), "request-001", "hash-001")
                .session();

        PayException exception = assertThrows(
                PayException.class,
                () -> service.selectPaymentMethod("user-002", session.id(), "method-001"));

        assertEquals(PayErrorCode.RESOURCE_NOT_FOUND, exception.errorCode());
    }

    @Test
    void onlyAnActiveCardOwnedByTheUserCanBeSelected() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        InMemoryPaymentMethodRepository paymentMethods = new InMemoryPaymentMethodRepository();
        AuthorizationSessionService service = service(store, paymentMethods);
        AuthorizationSession session = service.create(command(), "request-001", "hash-001")
                .session();
        paymentMethods.add(paymentMethod("method-002", "user-002", true));
        paymentMethods.add(new PaymentMethod(
                "method-003",
                "user-001",
                "PG_MOCK",
                "token-003",
                "VISA",
                "0003",
                12,
                2030,
                PaymentMethodStatus.REVOKED,
                false));

        PayException missing = assertThrows(
                PayException.class,
                () -> service.selectPaymentMethod("user-001", session.id(), "method-001"));
        PayException inactive = assertThrows(
                PayException.class,
                () -> service.selectPaymentMethod("user-001", session.id(), "method-003"));
        PayException anotherUser = assertThrows(
                PayException.class,
                () -> service.selectPaymentMethod("user-001", session.id(), "method-002"));

        assertEquals(PayErrorCode.PAYMENT_METHOD_NOT_FOUND, missing.errorCode());
        assertEquals(PayErrorCode.PAYMENT_METHOD_NOT_FOUND, inactive.errorCode());
        assertEquals(PayErrorCode.PAYMENT_METHOD_NOT_FOUND, anotherUser.errorCode());
    }

    @Test
    void terminalSessionsCannotChangePaymentMethod() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        InMemoryPaymentMethodRepository paymentMethods = new InMemoryPaymentMethodRepository();
        paymentMethods.add(paymentMethod("method-001", "user-001", true));
        AuthorizationSessionService service = service(store, paymentMethods);
        AuthorizationSession session = service.create(command(), "request-001", "hash-001")
                .session();

        for (AuthorizationSessionStatus status : List.of(
                AuthorizationSessionStatus.APPROVED,
                AuthorizationSessionStatus.COMPLETED,
                AuthorizationSessionStatus.REJECTED,
                AuthorizationSessionStatus.EXPIRED,
                AuthorizationSessionStatus.INVALIDATED)) {
            store.replaceStatus(session.id(), status);

            PayException exception = assertThrows(
                    PayException.class,
                    () -> service.selectPaymentMethod(
                            "user-001",
                            session.id(),
                            "method-001"));

            assertEquals(PayErrorCode.STATE_CONFLICT, exception.errorCode());
            assertEquals(status.name(), exception.currentState());
        }
    }

    @Test
    void sameIdempotencyKeyAndRequestReturnsTheExistingSession() {
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        AuthorizationSessionService service = service(
                new InMemoryAuthorizationSessionStore(),
                new InMemoryPaymentMethodRepository(),
                evidence);

        AuthorizationSessionService.CreateResult first = service.create(
                command(),
                "request-001",
                "hash-001");
        AuthorizationSessionService.CreateResult replay = service.create(
                command(),
                "request-001",
                "hash-001");

        assertTrue(replay.replay());
        assertEquals(first.session().id(), replay.session().id());
        assertEquals(1, evidence.items.size());
    }

    @Test
    void sameIdempotencyKeyAndDifferentRequestReturnsConflict() {
        AuthorizationSessionService service = service(
                new InMemoryAuthorizationSessionStore(),
                new InMemoryPaymentMethodRepository());
        service.create(command(), "request-001", "hash-001");

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(command(), "request-001", "hash-002"));

        assertEquals(PayErrorCode.IDEMPOTENCY_CONFLICT, exception.errorCode());
    }

    @Test
    void activeSessionForSameMerchantAndPurchaseRequestReturnsConflict() {
        AuthorizationSessionService service = service(
                new InMemoryAuthorizationSessionStore(),
                new InMemoryPaymentMethodRepository());
        service.create(command(), "request-001", "hash-001");

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(command(), "request-002", "hash-002"));

        assertEquals(PayErrorCode.STATE_CONFLICT, exception.errorCode());
        assertEquals(AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED.name(),
                exception.currentState());
    }

    @Test
    void listAndDetailOnlyExposeTheCurrentUsersSessions() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        AuthorizationSessionService service = service(store, new InMemoryPaymentMethodRepository());
        service.create(command(), "request-001", "hash-001");
        service.create(commandForUser("user-002"), "request-002", "hash-002");

        List<AuthorizationSession> sessions = service.findForUser(
                "user-001",
                null);

        assertEquals(1, sessions.size());
        assertEquals("user-001", sessions.getFirst().userId());
        assertEquals(
                sessions.getFirst().id(),
                service.findDetail("user-001", sessions.getFirst().id()).id());
        assertThrows(
                PayException.class,
                () -> service.findDetail("user-002", sessions.getFirst().id()));
    }

    @Test
    void invalidSignatureDoesNotCreateSessionOrEvidence() throws JOSEException {
        InMemoryAuthorizationSessionStore sessions = new InMemoryAuthorizationSessionStore();
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        ECKey otherKey = new ECKeyGenerator(Curve.P_256)
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.ES256)
                .generate();
        String checkoutJwt = signedCheckout(otherKey, KEY_ID, "merchant-001", "purchase-001");
        AuthorizationSessionService service = service(
                sessions,
                new InMemoryPaymentMethodRepository(),
                evidence);

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(
                        commandWithJwt(checkoutJwt),
                        "request-001",
                        "hash-001"));

        assertEquals(PayErrorCode.CHECKOUT_SIGNATURE_INVALID, exception.errorCode());
        assertTrue(sessions.sessions.isEmpty());
        assertTrue(evidence.items.isEmpty());
    }

    @Test
    void claimMismatchIsInvalidRequestAndDoesNotCreateSession() throws JOSEException {
        InMemoryAuthorizationSessionStore sessions = new InMemoryAuthorizationSessionStore();
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        String checkoutJwt = signedCheckout(signingKey, KEY_ID, "merchant-001", "different-purchase");
        AuthorizationSessionService service = service(
                sessions,
                new InMemoryPaymentMethodRepository(),
                evidence);

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(
                        commandWithJwt(checkoutJwt),
                        "request-001",
                        "hash-001"));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
        assertTrue(sessions.sessions.isEmpty());
        assertTrue(evidence.items.isEmpty());
    }

    @Test
    void checkoutHashMismatchIsInvalidRequestBeforeSignatureVerification() {
        InMemoryAuthorizationSessionStore sessions = new InMemoryAuthorizationSessionStore();
        InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
        AuthorizationSessionService service = service(
                sessions,
                new InMemoryPaymentMethodRepository(),
                evidence);
        CreateAuthorizationSessionCommand valid = command();
        CreateAuthorizationSessionCommand.Item validItem = valid.items().getFirst();
        CreateAuthorizationSessionCommand mismatched = new CreateAuthorizationSessionCommand(
                valid.purchaseRequestId(),
                valid.userId(),
                valid.totalAmountMinor(),
                valid.currency(),
                List.of(new CreateAuthorizationSessionCommand.Item(
                        validItem.merchantId(),
                        validItem.checkoutId(),
                        externalHash("another-checkout"),
                        validItem.checkoutJwt(),
                        validItem.displaySnapshot(),
                        validItem.amountMinor(),
                        validItem.currency())));

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(mismatched, "request-001", "hash-001"));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
        assertTrue(sessions.sessions.isEmpty());
        assertTrue(evidence.items.isEmpty());
    }

    @Test
    void totalAmountMustMatchTheSumOfItems() {
        // 사용자가 승인 화면에서 본 합계와 실제 결제 합계가 어긋나는 것을 여기서 막는다.
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        AuthorizationSessionService service = service(store, new InMemoryPaymentMethodRepository());
        CreateAuthorizationSessionCommand base = command();
        CreateAuthorizationSessionCommand wrongTotal = new CreateAuthorizationSessionCommand(
                base.purchaseRequestId(),
                base.userId(),
                base.totalAmountMinor() + 1,
                base.currency(),
                base.items());

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(wrongTotal, "request-001", "hash-001"));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
        assertTrue(store.sessions.isEmpty());
    }

    @Test
    void everyItemMustUseTheRequestCurrency() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        AuthorizationSessionService service = service(store, new InMemoryPaymentMethodRepository());
        CreateAuthorizationSessionCommand base = command();
        CreateAuthorizationSessionCommand.Item item = base.items().getFirst();
        CreateAuthorizationSessionCommand mixed = new CreateAuthorizationSessionCommand(
                base.purchaseRequestId(),
                base.userId(),
                base.totalAmountMinor(),
                "KRW",
                List.of(new CreateAuthorizationSessionCommand.Item(
                        item.merchantId(),
                        item.checkoutId(),
                        item.checkoutHash(),
                        item.checkoutJwt(),
                        item.displaySnapshot(),
                        item.amountMinor(),
                        "USD")));

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(mixed, "request-001", "hash-001"));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
        assertTrue(store.sessions.isEmpty());
    }

    @Test
    void theSameMerchantCannotAppearTwiceInOneRequest() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        AuthorizationSessionService service = service(store, new InMemoryPaymentMethodRepository());
        CreateAuthorizationSessionCommand base = command();
        CreateAuthorizationSessionCommand.Item item = base.items().getFirst();
        CreateAuthorizationSessionCommand duplicated = new CreateAuthorizationSessionCommand(
                base.purchaseRequestId(),
                base.userId(),
                item.amountMinor() * 2,
                base.currency(),
                List.of(item, item));

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(duplicated, "request-001", "hash-001"));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
        assertTrue(store.sessions.isEmpty());
    }

    @Test
    void emptyItemsIsRejected() {
        InMemoryAuthorizationSessionStore store = new InMemoryAuthorizationSessionStore();
        AuthorizationSessionService service = service(store, new InMemoryPaymentMethodRepository());
        CreateAuthorizationSessionCommand base = command();
        CreateAuthorizationSessionCommand empty = new CreateAuthorizationSessionCommand(
                base.purchaseRequestId(),
                base.userId(),
                0,
                base.currency(),
                List.of());

        PayException exception = assertThrows(
                PayException.class,
                () -> service.create(empty, "request-001", "hash-001"));

        assertEquals(PayErrorCode.INVALID_REQUEST, exception.errorCode());
        assertTrue(store.sessions.isEmpty());
    }

    private AuthorizationSessionService service(
            InMemoryAuthorizationSessionStore store,
            InMemoryPaymentMethodRepository paymentMethods) {
        return service(store, paymentMethods, new InMemoryEvidenceRepository());
    }

    private AuthorizationSessionService service(
            InMemoryAuthorizationSessionStore store,
            InMemoryPaymentMethodRepository paymentMethods,
            InMemoryEvidenceRepository evidence) {
        return new AuthorizationSessionService(
                store,
                new InMemoryMerchantAccountRepository(),
                paymentMethods,
                new IdempotencyGuard(),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ApprovalProperties(300, 60, true),
                new MerchantCheckoutVerifier(
                        merchantPublicKeyClient(),
                        new MerchantServiceProperties(
                                "http://merchant.test",
                                "/.well-known/ucp",
                                1000,
                                1000,
                                "ES256"),
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                evidence);
    }

    private CreateAuthorizationSessionCommand command() {
        return commandForUser("user-001");
    }

    private CreateAuthorizationSessionCommand commandForUser(String userId) {
        String purchaseRequestId = "user-001".equals(userId) ? "purchase-001" : "purchase-002";
        String checkoutJwt = signedCheckout(
                signingKey,
                KEY_ID,
                "merchant-001",
                purchaseRequestId);
        return new CreateAuthorizationSessionCommand(
                purchaseRequestId,
                userId,
                99000,
                "KRW",
                List.of(new CreateAuthorizationSessionCommand.Item(
                        "merchant-001",
                        "checkout-001",
                        externalHash(checkoutJwt),
                        checkoutJwt,
                        new AuthorizationDisplaySnapshot(
                                "예시 상점",
                                List.of(new AuthorizationDisplaySnapshot.SummaryItem(
                                        "러닝화",
                                        "270",
                                        1,
                                        99000))),
                        99000,
                        "KRW")));
    }

    private CreateAuthorizationSessionCommand commandWithJwt(String checkoutJwt) {
        CreateAuthorizationSessionCommand base = command();
        CreateAuthorizationSessionCommand.Item baseItem = base.items().getFirst();
        return new CreateAuthorizationSessionCommand(
                base.purchaseRequestId(),
                base.userId(),
                base.totalAmountMinor(),
                base.currency(),
                List.of(new CreateAuthorizationSessionCommand.Item(
                        baseItem.merchantId(),
                        baseItem.checkoutId(),
                        externalHash(checkoutJwt),
                        checkoutJwt,
                        baseItem.displaySnapshot(),
                        baseItem.amountMinor(),
                        baseItem.currency())));
    }

    private PaymentMethod paymentMethod(
            String id,
            String userId,
            boolean defaultMethod) {
        return new PaymentMethod(
                id,
                userId,
                "PG_MOCK",
                "token-" + id,
                "VISA",
                "1234",
                12,
                2030,
                PaymentMethodStatus.ACTIVE,
                defaultMethod);
    }

    private MerchantPublicKeyClient merchantPublicKeyClient() {
        return merchantId -> List.of(signingKey.toPublicJWK().toJSONObject());
    }

    private String signedCheckout(
            ECKey key,
            String keyId,
            String merchantId,
            String purchaseRequestId) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("merchantId", merchantId)
                .claim("checkoutId", "checkout-001")
                .claim("purchaseRequestId", purchaseRequestId)
                .claim("amountMinor", 99000)
                .claim("currency", "KRW")
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .build();
        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(),
                claims);
        try {
            signedJwt.sign(new ECDSASigner(key.toECPrivateKey()));
            return signedJwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("test checkout signing failed", exception);
        }
    }

    private String externalHash(String checkoutJwt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(checkoutJwt.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static final class InMemoryMerchantAccountRepository
            implements MerchantAccountRepository {

        @Override
        public Optional<MerchantAccount> findActiveByMerchantId(String merchantId) {
            if (!"merchant-001".equals(merchantId)) {
                return Optional.empty();
            }
            return Optional.of(new MerchantAccount(
                    "merchant-account-001",
                    merchantId,
                    "예시 상점",
                    "ACTIVE"));
        }
    }

    private static final class InMemoryPaymentMethodRepository
            implements PaymentMethodRepository {

        private final List<PaymentMethod> paymentMethods = new ArrayList<>();

        @Override
        public List<PaymentMethod> findActiveByUserId(String userId) {
            return paymentMethods.stream()
                    .filter(paymentMethod -> paymentMethod.userId().equals(userId))
                    .filter(paymentMethod -> paymentMethod.status() == PaymentMethodStatus.ACTIVE)
                    .toList();
        }

        @Override
        public Optional<PaymentMethod> findActiveByIdAndUserIdForUpdate(
                String paymentMethodId,
                String userId) {
            return paymentMethods.stream()
                    .filter(paymentMethod -> paymentMethod.id().equals(paymentMethodId))
                    .filter(paymentMethod -> paymentMethod.userId().equals(userId))
                    .filter(paymentMethod -> paymentMethod.status() == PaymentMethodStatus.ACTIVE)
                    .findFirst();
        }

        @Override
        public void insert(PaymentMethod paymentMethod) {
            paymentMethods.add(paymentMethod);
        }

        @Override
        public int clearDefaultForUser(String userId) {
            return 0;
        }

        @Override
        public int markDefault(String paymentMethodId, String userId) {
            return 0;
        }

        @Override
        public int softDelete(String paymentMethodId, String userId) {
            return 0;
        }

        private void add(PaymentMethod paymentMethod) {
            paymentMethods.add(paymentMethod);
        }
    }

    private static final class InMemoryAuthorizationSessionStore
            implements AuthorizationSessionStore {

        private final Map<String, AuthorizationSession> sessions = new HashMap<>();
        @Override
        public Optional<AuthorizationSession> findByUserAndIdempotencyKey(
                String userId,
                String idempotencyKey) {
            return sessions.values().stream()
                    .filter(session -> session.userId().equals(userId))
                    .filter(session -> session.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<AuthorizationSession> findActiveByPurchaseRequest(
                String purchaseRequestId) {
            return sessions.values().stream()
                    .filter(session -> session.purchaseRequestId().equals(purchaseRequestId))
                    .filter(session -> session.status().isActive())
                    .findFirst();
        }

        @Override
        public boolean insert(AuthorizationSession session) {
            boolean duplicateIdempotency = sessions.values().stream()
                    .anyMatch(existing -> existing.userId().equals(session.userId())
                            && existing.idempotencyKey().equals(session.idempotencyKey()));
            boolean duplicateActiveRequest =
                    findActiveByPurchaseRequest(session.purchaseRequestId()).isPresent();
            if (duplicateIdempotency || duplicateActiveRequest) {
                return false;
            }
            sessions.put(session.id(), session);
            return true;
        }

        @Override
        public List<AuthorizationSession> findByUserId(
                String userId,
                AuthorizationSessionStatus status) {
            return sessions.values().stream()
                    .filter(session -> session.userId().equals(userId))
                    .filter(session -> status == null
                            ? session.status().isActive()
                            : session.status() == status)
                    .toList();
        }

        @Override
        public Optional<AuthorizationSession> findByIdAndUserId(
                String authorizationSessionId,
                String userId) {
            return Optional.ofNullable(sessions.get(authorizationSessionId))
                    .filter(session -> session.userId().equals(userId));
        }

        @Override
        public int expireOverdueForUser(String userId, Instant now) {
            return 0;
        }

        @Override
        public int expireOverdueByIdAndUser(
                String authorizationSessionId,
                String userId,
                Instant now) {
            return 0;
        }

        @Override
        public boolean selectPaymentMethod(
                String authorizationSessionId,
                String userId,
                String paymentMethodId,
                Instant now) {
            AuthorizationSession current = sessions.get(authorizationSessionId);
            if (current == null
                    || !current.userId().equals(userId)
                    || !now.isBefore(current.expiresAt())
                    || current.status() != AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                    && current.status() != AuthorizationSessionStatus.PENDING) {
                return false;
            }
            AuthorizationSessionStatus nextStatus = current.status()
                    == AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED
                    ? AuthorizationSessionStatus.PENDING
                    : current.status();
            sessions.put(
                    authorizationSessionId,
                    new AuthorizationSession(
                            current.id(),
                            current.purchaseRequestId(),
                            current.userId(),
                            current.totalAmountMinor(),
                            current.currency(),
                            paymentMethodId,
                            nextStatus,
                            current.idempotencyKey(),
                            current.requestHash(),
                            current.requiredAction(),
                            current.expiresAt(),
                            current.approvedAt(),
                            current.authMethod(),
                            current.createdAt(),
                            now,
                            current.version() + 1,
                            current.items()));
            return true;
        }

        private void replaceStatus(
                String authorizationSessionId,
                AuthorizationSessionStatus status) {
            AuthorizationSession current = sessions.get(authorizationSessionId);
            sessions.put(
                    authorizationSessionId,
                    new AuthorizationSession(
                            current.id(),
                            current.purchaseRequestId(),
                            current.userId(),
                            current.totalAmountMinor(),
                            current.currency(),
                            current.selectedPaymentMethodId(),
                            status,
                            current.idempotencyKey(),
                            current.requestHash(),
                            current.requiredAction(),
                            current.expiresAt(),
                            current.approvedAt(),
                            current.authMethod(),
                            current.createdAt(),
                            current.updatedAt(),
                            current.version(),
                            current.items()));
        }
    }

    private static final class InMemoryEvidenceRepository
            implements VerifiedCheckoutEvidenceRepository {

        private final List<VerifiedCheckoutEvidence> items = new ArrayList<>();

        @Override
        public boolean insert(VerifiedCheckoutEvidence evidence) {
            if (items.stream().anyMatch(existing -> existing.sessionItemId()
                    .equals(evidence.sessionItemId()))) {
                return false;
            }
            items.add(evidence);
            return true;
        }
    }
}
