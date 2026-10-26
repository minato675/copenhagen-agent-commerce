package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.credential.mandate.ArtifactEncryptionService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Called inside the settlement transaction after locking the Payment row. */
@Service
public class PaymentReceiptService {

    private final PaymentExecutionRepository repository;
    private final ArtifactEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    public PaymentReceiptService(
            PaymentExecutionRepository repository,
            ArtifactEncryptionService encryptionService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper;
    }

    public void issueIfAbsent(PaymentExecutionRecord payment, String pgTransactionId, Instant issuedAt) {
        if (payment.receiptId() != null) {
            return;
        }
        Map<String, Object> receiptBody = new LinkedHashMap<>();
        receiptBody.put("paymentId", payment.id());
        receiptBody.put("attemptId", payment.attemptId());
        receiptBody.put("pgTransactionId", pgTransactionId);
        receiptBody.put("amountMinor", payment.amountMinor());
        receiptBody.put("currency", payment.currency());
        receiptBody.put("status", "SUCCESS");
        receiptBody.put("issuedAt", issuedAt.toString());
        ArtifactEncryptionService.EncryptedArtifact encrypted = encryptionService.encrypt(
                objectMapper.writeValueAsString(receiptBody));
        repository.insertReceipt(new PaymentExecutionRepository.PaymentReceipt(
                UUID.randomUUID().toString(), payment.id(), encrypted.hash(), encrypted.ciphertext(), issuedAt));
    }
}
