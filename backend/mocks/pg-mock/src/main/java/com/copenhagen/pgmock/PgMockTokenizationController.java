package com.copenhagen.pgmock;

import com.copenhagen.pgmock.PgMockTokenizationService.IdempotencyConflictException;
import com.copenhagen.pgmock.PgMockTokenizationService.InvalidCardDataException;
import com.copenhagen.pgmock.PgMockTokenizationService.InvalidIdempotencyKeyException;
import com.copenhagen.pgmock.PgMockTokenizationService.MissingIdempotencyKeyException;
import com.copenhagen.pgmock.PgMockTokenizationService.TokenizationInternalException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pg/v1/payment-methods/tokenize")
public class PgMockTokenizationController {

    private final PgMockTokenizationService service;

    public PgMockTokenizationController(PgMockTokenizationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> tokenize(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) TokenizationRequest request) {
        try {
            return ResponseEntity.ok(service.tokenize(idempotencyKey, request));
        } catch (InvalidCardDataException exception) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("INVALID_CARD_DATA", "카드 정보가 올바르지 않습니다."));
        } catch (MissingIdempotencyKeyException exception) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("IDEMPOTENCY_KEY_REQUIRED", "유효한 Idempotency-Key가 필요합니다."));
        } catch (InvalidIdempotencyKeyException exception) {
            return ResponseEntity.badRequest().body(
                    new ErrorResponse("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key는 UUID 형식이어야 합니다."));
        } catch (IdempotencyConflictException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    new ErrorResponse(
                            "IDEMPOTENCY_CONFLICT",
                            "같은 Idempotency-Key로 다른 요청을 보낼 수 없습니다."));
        } catch (TokenizationInternalException exception) {
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse("INTERNAL_ERROR", "토큰화 결과를 생성할 수 없습니다."));
        }
    }

    public record TokenizationRequest(
            String cardNumber,
            Integer expiryMonth,
            Integer expiryYear,
            String cvc,
            String cardPassword) {

        @Override
        public String toString() {
            return "TokenizationRequest[cardNumber=<redacted>, expiryMonth="
                    + expiryMonth
                    + ", expiryYear="
                    + expiryYear
                    + ", cvc=<redacted>, cardPassword=<redacted>]";
        }
    }

    public record TokenizationResponse(
            String provider,
            String providerTokenRef,
            String brand,
            String last4,
            Integer expiryMonth,
            Integer expiryYear) {

        @Override
        public String toString() {
            return "TokenizationResponse[provider='" + provider
                    + "', providerTokenRef=<redacted>, brand='" + brand
                    + "', last4='" + last4
                    + "', expiryMonth=" + expiryMonth
                    + ", expiryYear=" + expiryYear + "]";
        }
    }

    public record ErrorResponse(String code, String message) {
    }
}
