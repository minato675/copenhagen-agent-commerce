package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.authorization.AuthorizationSession;
import com.copenhagen.pay.payment.authorization.AuthorizationSessionItem;
import com.copenhagen.pay.payment.authorization.CheckoutHashCodec;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethod;
import com.copenhagen.pay.payment.paymentmethod.PaymentMethodRepository;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MandateCredentialService implements PaymentCredentialIssuer {

    private final MandateProperties properties;
    private final MandateSigningService signingService;
    private final ArtifactEncryptionService encryptionService;
    private final MandateCredentialRepository repository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MandateCredentialService(
            MandateProperties properties,
            MandateSigningService signingService,
            ArtifactEncryptionService encryptionService,
            MandateCredentialRepository repository,
            PaymentMethodRepository paymentMethodRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.signingService = signingService;
        this.encryptionService = encryptionService;
        this.repository = repository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public IssuedPaymentCredential issue(
            AuthorizationSession session,
            AuthorizationSessionItem item,
            String authMethod) {
        Instant issuedAt = clock.instant();
        if (session.status() != AuthorizationSessionStatus.APPROVED) {
            throw new PayException(
                    PayErrorCode.STATE_CONFLICT,
                    "승인 완료된 세션에서만 결제 자격증명을 발급할 수 있습니다.",
                    session.status().name(),
                    "COMPLETE_APPROVAL");
        }
        if (!issuedAt.isBefore(session.expiresAt())) {
            throw new PayException(
                    PayErrorCode.STATE_CONFLICT,
                    "만료된 승인 세션에서는 결제 자격증명을 발급할 수 없습니다.",
                    session.status().name(),
                    "RESTART_CHECKOUT");
        }
        if (session.selectedPaymentMethodId() == null) {
            throw new PayException(
                    PayErrorCode.PAYMENT_METHOD_NOT_FOUND,
                    PayErrorCode.PAYMENT_METHOD_NOT_FOUND.defaultMessage(),
                    session.status().name(),
                    "REGISTER_OR_SELECT_PAYMENT_METHOD");
        }

        String checkoutMandateId = UUID.randomUUID().toString();
        String paymentMandateId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String credentialId = UUID.randomUUID().toString();
        PaymentMethod paymentMethod = paymentMethodRepository
                .findActiveByIdAndUserIdForUpdate(
                        session.selectedPaymentMethodId(),
                        session.userId())
                .orElseThrow(() -> new PayException(PayErrorCode.PAYMENT_METHOD_INACTIVE));
        String paymentMethodRef = paymentMethod.providerTokenRef();
        String checkoutHash = CheckoutHashCodec.toExternalHash(item.checkoutDigest());
        String audience = "merchant:" + item.merchantId();

        String checkoutMandate = signingService.createCheckoutMandate(
                session,
                item,
                checkoutMandateId,
                issuedAt);
        ArtifactEncryptionService.EncryptedArtifact checkoutArtifact = encryptionService
                .encrypt(checkoutMandate);

        String paymentMandate = signingService.createPaymentMandate(
                session,
                item,
                paymentMandateId,
                transactionId,
                paymentMethodRef,
                issuedAt);
        ArtifactEncryptionService.EncryptedArtifact paymentArtifact = encryptionService
                .encrypt(paymentMandate);

        String credentialArtifact = serializeCredentialArtifact(
                credentialId,
                checkoutMandateId,
                paymentMandateId,
                paymentMandate,
                session,
                item,
                checkoutHash,
                authMethod,
                paymentMethodRef);
        ArtifactEncryptionService.EncryptedArtifact credentialToken = encryptionService
                .encrypt(credentialArtifact);

        repository.insert(new MandateCredentialRepository.Issuance(
                checkoutMandateId,
                session.id(),
                item.id(),
                checkoutMandateId,
                properties.issuer(),
                audience,
                item.checkoutDigest(),
                checkoutArtifact.ciphertext(),
                checkoutArtifact.hash(),
                issuedAt,
                session.expiresAt(),
                paymentMandateId,
                paymentMandateId,
                transactionId,
                item.merchantId(),
                item.externalCheckoutId(),
                item.amountMinor(),
                item.currency(),
                paymentMethodRef,
                paymentArtifact.ciphertext(),
                paymentArtifact.hash(),
                credentialId,
                ArtifactEncryptionService.sha256Hex(credentialId),
                credentialToken.ciphertext()));

        return new IssuedPaymentCredential(
                credentialId,
                session.expiresAt(),
                checkoutMandate);
    }

    private String serializeCredentialArtifact(
            String credentialId,
            String checkoutMandateId,
            String paymentMandateId,
            String paymentMandate,
            AuthorizationSession session,
            AuthorizationSessionItem item,
            String checkoutHash,
            String authMethod,
            String paymentMethodRef) {
        try {
            return objectMapper.writeValueAsString(new CredentialArtifact(
                    credentialId,
                    checkoutMandateId,
                    paymentMandateId,
                    paymentMandate,
                    item.merchantId(),
                    item.externalCheckoutId(),
                    checkoutHash,
                    session.purchaseRequestId(),
                    item.amountMinor(),
                    item.currency(),
                    paymentMethodRef,
                    authMethod,
                    session.expiresAt()));
        } catch (JacksonException exception) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    "결제 자격증명을 저장할 수 없습니다.",
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }
    }

    private record CredentialArtifact(
            String credentialId,
            String checkoutMandateId,
            String paymentMandateId,
            String paymentMandate,
            String merchantId,
            String checkoutId,
            String checkoutHash,
            String purchaseRequestId,
            long amountMinor,
            String currency,
            String paymentMethodRef,
            String authMethod,
            Instant expiresAt) {
    }
}
