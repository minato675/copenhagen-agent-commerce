package com.copenhagen.pgmock;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PgMockTokenizationService {

    private static final Pattern CARD_NUMBER_PATTERN = Pattern.compile("\\d{13,19}");
    private static final Pattern CVC_PATTERN = Pattern.compile("\\d{3,4}");
    private static final Pattern CARD_PASSWORD_PATTERN = Pattern.compile("\\d{4}");
    private static final String PROVIDER = "mock-pg";

    private final Map<String, TokenizationRecord> records = new ConcurrentHashMap<>();
    private final Clock clock;

    public PgMockTokenizationService() {
        this(Clock.systemUTC());
    }

    PgMockTokenizationService(Clock clock) {
        this.clock = clock;
    }

    public PgMockTokenizationController.TokenizationResponse tokenize(
            String idempotencyKey,
            PgMockTokenizationController.TokenizationRequest request) {
        requireIdempotencyKey(idempotencyKey);
        ValidatedCard card = validate(request);
        String requestHash = hash(request);

        TokenizationRecord record = records.compute(idempotencyKey, (key, existing) -> {
            if (existing != null) {
                if (!existing.requestHash().equals(requestHash)) {
                    throw new IdempotencyConflictException();
                }
                return existing;
            }
            return new TokenizationRecord(requestHash, createResponse(card));
        });
        return record.response();
    }

    private ValidatedCard validate(
            PgMockTokenizationController.TokenizationRequest request) {
        if (request == null
                || !isValidCardNumber(request.cardNumber())
                || !isValidExpiry(request.expiryMonth(), request.expiryYear())
                || !isValidCvc(request.cvc())
                || !isValidCardPassword(request.cardPassword())) {
            throw new InvalidCardDataException();
        }
        return new ValidatedCard(
                request.cardNumber(),
                request.expiryMonth(),
                request.expiryYear());
    }

    private boolean isValidCardNumber(String cardNumber) {
        return cardNumber != null
                && CARD_NUMBER_PATTERN.matcher(cardNumber).matches()
                && passesLuhn(cardNumber);
    }

    private boolean isValidExpiry(Integer expiryMonth, Integer expiryYear) {
        if (expiryMonth == null || expiryYear == null || expiryYear < 1000 || expiryYear > 9999) {
            return false;
        }
        try {
            YearMonth expiry = YearMonth.of(expiryYear, expiryMonth);
            return !expiry.isBefore(YearMonth.now(clock));
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private boolean isValidCvc(String cvc) {
        return cvc != null && CVC_PATTERN.matcher(cvc).matches();
    }

    private boolean isValidCardPassword(String cardPassword) {
        return cardPassword == null || CARD_PASSWORD_PATTERN.matcher(cardPassword).matches();
    }

    private boolean passesLuhn(String cardNumber) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = cardNumber.length() - 1; index >= 0; index--) {
            int digit = cardNumber.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }

    private PgMockTokenizationController.TokenizationResponse createResponse(ValidatedCard card) {
        return new PgMockTokenizationController.TokenizationResponse(
                PROVIDER,
                "tok_mock_" + UUID.randomUUID().toString().replace("-", ""),
                brand(card.cardNumber()),
                card.cardNumber().substring(card.cardNumber().length() - 4),
                card.expiryMonth(),
                card.expiryYear());
    }

    private String brand(String cardNumber) {
        if (cardNumber.startsWith("4")) {
            return "VISA";
        }
        if (isMastercard(cardNumber)) {
            return "MASTERCARD";
        }
        if (cardNumber.startsWith("34") || cardNumber.startsWith("37")) {
            return "AMEX";
        }
        return "UNKNOWN";
    }

    private boolean isMastercard(String cardNumber) {
        if (cardNumber.length() >= 2) {
            int twoDigitPrefix = Integer.parseInt(cardNumber.substring(0, 2));
            if (twoDigitPrefix >= 51 && twoDigitPrefix <= 55) {
                return true;
            }
        }
        if (cardNumber.length() < 4) {
            return false;
        }
        int fourDigitPrefix = Integer.parseInt(cardNumber.substring(0, 4));
        return fourDigitPrefix >= 2221 && fourDigitPrefix <= 2720;
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }
        if (!isUuid(idempotencyKey)) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    private boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String hash(PgMockTokenizationController.TokenizationRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, request.cardNumber());
            update(digest, request.expiryMonth());
            update(digest, request.expiryYear());
            update(digest, request.cvc());
            update(digest, request.cardPassword());
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new TokenizationInternalException(exception);
        }
    }

    private void update(MessageDigest digest, Object value) {
        String encoded = value == null ? "<null>" : value.toString();
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private record ValidatedCard(String cardNumber, Integer expiryMonth, Integer expiryYear) {
    }

    private record TokenizationRecord(
            String requestHash,
            PgMockTokenizationController.TokenizationResponse response) {
    }

    public static class InvalidCardDataException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    public static class InvalidIdempotencyKeyException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    public static class MissingIdempotencyKeyException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    public static class IdempotencyConflictException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    public static class TokenizationInternalException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public TokenizationInternalException(Throwable cause) {
            super(cause);
        }
    }
}
