package com.copenhagen.pay.payment.pg;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeRequest;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult.Outcome;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * PG Mock과 통신하는 Pay 측 어댑터입니다.
 *
 * <p>결제 요청에는 재시도를 적용하지 않습니다. 네트워크 응답이 사라지면 해당 요청이 PG에
 * 도달했을 가능성을 배제할 수 없으므로 결과를 UNKNOWN으로 남깁니다.
 */
@Component
public class PgMockPaymentGateway implements PaymentGateway {

    private static final String CHARGE_PATH = "/internal/v1/charges";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String INVALID_RESPONSE_CODE = "PG_RESPONSE_CODE_INVALID";
    private static final Pattern SAFE_RESPONSE_CODE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Logger log = LoggerFactory.getLogger(PgMockPaymentGateway.class);

    private final PgProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI chargeUri;

    public PgMockPaymentGateway(PgProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis()))
                .build();
        this.chargeUri = createChargeUri(properties.baseUrl());
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        long startedAt = System.nanoTime();
        PayLogContext.put(PayLogContext.PAYMENT_ID, request.paymentId());
        log.info(
                "pay.pg.charge.start pgRequestId={}",
                request.pgRequestId());
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new PayException(
                    PayErrorCode.INTERNAL_ERROR,
                    "PG 결제 요청을 직렬화할 수 없습니다.",
                    null,
                    PayErrorCode.INTERNAL_ERROR.defaultNextAction(),
                    exception);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(chargeUri)
                .timeout(Duration.ofMillis(properties.requestTimeoutMillis()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(REQUEST_ID_HEADER, PayLogContext.requestIdOrNew())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            ChargeResult result = mapResponse(response);
            log.info(
                    "pay.pg.charge.complete httpStatus={} outcome={} pgErrorCode={} durationMs={}",
                    response.statusCode(),
                    result.outcome(),
                    result.responseCode(),
                    elapsedMillis(startedAt));
            return result;
        } catch (HttpTimeoutException exception) {
            log.warn(
                    "pay.pg.charge.timeout reason=PG_TIMEOUT durationMs={}",
                    elapsedMillis(startedAt));
            return ChargeResult.unknown("PG_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn(
                    "pay.pg.charge.interrupted durationMs={}",
                    elapsedMillis(startedAt));
            return ChargeResult.unknown("PG_REQUEST_INTERRUPTED");
        } catch (IOException exception) {
            log.warn(
                    "pay.pg.charge.unavailable reason=PG_RESPONSE_UNAVAILABLE durationMs={}",
                    elapsedMillis(startedAt));
            return ChargeResult.unknown("PG_RESPONSE_UNAVAILABLE");
        }
    }

    @Override
    public StatusResult lookup(StatusRequest request) {
        if (request == null) {
            log.warn("pay.pg.lookup.rejected reason=REQUEST_MISSING");
            return StatusResult.unknown("PG_LOOKUP_IDENTIFIER_MISSING");
        }
        long startedAt = System.nanoTime();
        String key = "pgTransactionId";
        String identifier = request.pgTransactionId();
        if (identifier == null || identifier.isBlank()) {
            key = "pgRequestId";
            identifier = request.pgRequestId();
        }
        if (identifier == null || identifier.isBlank()) {
            log.warn("pay.pg.lookup.rejected reason=IDENTIFIER_MISSING");
            return StatusResult.unknown("PG_LOOKUP_IDENTIFIER_MISSING");
        }
        log.info(
                "pay.pg.lookup.start identifierType={} identifierHash={}",
                key,
                PayLogContext.fingerprint(identifier));
        URI uri = URI.create(chargeUri + "?" + key + "="
                + URLEncoder.encode(identifier, StandardCharsets.UTF_8));
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.requestTimeoutMillis()))
                .header("Accept", "application/json")
                .header(REQUEST_ID_HEADER, PayLogContext.requestIdOrNew())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            // A missing transaction or a lookup authorization error is not a declined charge.
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "pay.pg.lookup.complete httpStatus={} outcome=UNKNOWN pgErrorCode={} durationMs={}",
                        response.statusCode(),
                        "PG_LOOKUP_HTTP_" + response.statusCode(),
                        elapsedMillis(startedAt));
                return StatusResult.unknown("PG_LOOKUP_HTTP_" + response.statusCode());
            }
            ChargeResult result = mapResponse(response);
            if (result.outcome() == Outcome.SUCCESS
                    && (result.pgTransactionId() == null || result.pgTransactionId().isBlank())) {
                log.warn(
                        "pay.pg.lookup.complete httpStatus={} outcome=UNKNOWN pgErrorCode={} durationMs={}",
                        response.statusCode(),
                        "PG_INVALID_SUCCESS_RESPONSE",
                        elapsedMillis(startedAt));
                return StatusResult.unknown("PG_INVALID_SUCCESS_RESPONSE");
            }
            if (request.pgTransactionId() != null && !request.pgTransactionId().isBlank()
                    && result.pgTransactionId() != null
                    && !request.pgTransactionId().equals(result.pgTransactionId())) {
                log.warn(
                        "pay.pg.lookup.complete httpStatus={} outcome=UNKNOWN pgErrorCode={} durationMs={}",
                        response.statusCode(),
                        "PG_LOOKUP_TRANSACTION_MISMATCH",
                        elapsedMillis(startedAt));
                return StatusResult.unknown("PG_LOOKUP_TRANSACTION_MISMATCH");
            }
            String code = switch (result.outcome()) {
                case SUCCESS -> "PG_APPROVED";
                case FAILED -> "PG_DECLINED";
                case UNKNOWN -> "PG_LOOKUP_UNCONFIRMED";
            };
            log.info(
                    "pay.pg.lookup.complete httpStatus={} outcome={} pgErrorCode={} durationMs={}",
                    response.statusCode(),
                    result.outcome(),
                    code,
                    elapsedMillis(startedAt));
            return new StatusResult(result.outcome(), result.pgTransactionId(), code);
        } catch (HttpTimeoutException exception) {
            log.warn(
                    "pay.pg.lookup.timeout reason=PG_LOOKUP_TIMEOUT durationMs={}",
                    elapsedMillis(startedAt));
            return StatusResult.unknown("PG_LOOKUP_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn(
                    "pay.pg.lookup.interrupted durationMs={}",
                    elapsedMillis(startedAt));
            return StatusResult.unknown("PG_LOOKUP_INTERRUPTED");
        } catch (IOException exception) {
            log.warn(
                    "pay.pg.lookup.unavailable reason=PG_LOOKUP_UNAVAILABLE durationMs={}",
                    elapsedMillis(startedAt));
            return StatusResult.unknown("PG_LOOKUP_UNAVAILABLE");
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private ChargeResult mapResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() >= 500) {
                return ChargeResult.unknown("PG_HTTP_" + response.statusCode());
            }
            return ChargeResult.failed(null, "PG_HTTP_" + response.statusCode());
        }

        PgChargeResponse pgResponse;
        try {
            pgResponse = objectMapper.readValue(response.body(), PgChargeResponse.class);
        } catch (JacksonException | IllegalArgumentException exception) {
            return ChargeResult.unknown("PG_INVALID_RESPONSE");
        }
        if (pgResponse == null || pgResponse.status() == null) {
            return ChargeResult.unknown("PG_INVALID_RESPONSE");
        }

        try {
            Outcome outcome = Outcome.valueOf(pgResponse.status().toUpperCase(java.util.Locale.ROOT));
            return new ChargeResult(
                    outcome,
                    pgResponse.pgTransactionId(),
                    safeResponseCode(pgResponse.responseCode()));
        } catch (IllegalArgumentException exception) {
            return ChargeResult.unknown("PG_INVALID_STATUS");
        }
    }

    private String safeResponseCode(String responseCode) {
        if (responseCode == null || responseCode.isBlank()) {
            return null;
        }
        String trimmed = responseCode.trim();
        return SAFE_RESPONSE_CODE.matcher(trimmed).matches()
                ? trimmed
                : INVALID_RESPONSE_CODE;
    }

    private URI createChargeUri(String baseUrl) {
        try {
            return URI.create(baseUrl.replaceAll("/+$", "") + CHARGE_PATH);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("services.pg.base-url must be a valid URI", exception);
        }
    }

    public record PgChargeResponse(
            String status,
            String pgTransactionId,
            String responseCode) {
    }
}
