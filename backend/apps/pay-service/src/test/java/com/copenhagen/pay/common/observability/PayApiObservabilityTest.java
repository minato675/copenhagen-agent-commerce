package com.copenhagen.pay.common.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.copenhagen.pay.common.error.GlobalExceptionHandler;
import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class PayApiObservabilityTest {

    private static final String SENSITIVE_VALUES =
            "PIN=123456 CARD=4111111111111111 Authorization=Bearer synthetic-auth-token "
                    + "Cookie=synthetic-cookie credential=synthetic-payment-credential "
                    + "FCM=synthetic-fcm-token";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SyntheticPayController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .addFilters(new PayRequestLogFilter())
                .build();
    }

    @AfterEach
    void clearContext() {
        PayLogContext.clear();
    }

    @Test
    void normalRequestHasOneStartAndOneCompletionWithTraceFields() throws Exception {
        try (PayLogCapture logs = new PayLogCapture()) {
            var result = mockMvc.perform(get("/synthetic/normal")
                            .header(PayRequestLogFilter.REQUEST_ID_HEADER, "stub-request-001")
                            .header("X-App-Version", "1.2.3")
                            .header("X-OS-Version", "Android 15")
                            .header("X-Platform", "ANDROID"))
                    .andExpect(status().isOk())
                    .andReturn();

            assertEquals(
                    "stub-request-001",
                    result.getResponse().getHeader(PayRequestLogFilter.REQUEST_ID_HEADER));
            assertEquals(1, logs.count("pay.request.start"));
            assertEquals(1, logs.count("pay.request.complete"));
            assertTrue(logs.allText().contains("method=GET path=/synthetic/normal status=200"));
            assertTrue(logs.allText().contains("durationMs="));
            assertTrue(logs.events().stream().anyMatch(event ->
                    "stub-request-001".equals(
                            event.getMDCPropertyMap().get(PayLogContext.REQUEST_ID))));
        }
    }

    @Test
    void validationFailureHasSafeErrorCodeAndSeparateCompletionLog() throws Exception {
        try (PayLogCapture logs = new PayLogCapture()) {
            mockMvc.perform(post("/synthetic/validation")
                            .header(PayRequestLogFilter.REQUEST_ID_HEADER, "validation-001")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            assertEquals(1, logs.count("pay.request.validation_failed"));
            assertEquals(1, logs.count("pay.request.complete"));
            assertTrue(logs.allText().contains("errorCode=INVALID_REQUEST"));
            assertTrue(logs.allText().contains("status=400"));
        }
    }

    @Test
    void payExceptionHasBusinessErrorCodeAndNoDuplicateCompletionError() throws Exception {
        try (PayLogCapture logs = new PayLogCapture()) {
            mockMvc.perform(get("/synthetic/pay-exception")
                            .header(PayRequestLogFilter.REQUEST_ID_HEADER, "business-001"))
                    .andExpect(status().isConflict());

            assertEquals(1, logs.count("pay.request.rejected"));
            assertEquals(1, logs.count("pay.request.complete"));
            assertTrue(logs.allText().contains("errorCode=STATE_CONFLICT"));
            assertTrue(logs.allText().contains("method=GET path=/synthetic/pay-exception status=409"));
        }
    }

    @Test
    void unexpectedFailureHasSafeClassificationWithoutExceptionMessage() throws Exception {
        try (PayLogCapture logs = new PayLogCapture()) {
            mockMvc.perform(get("/synthetic/unexpected")
                            .header(PayRequestLogFilter.REQUEST_ID_HEADER, "unexpected-001"))
                    .andExpect(status().isInternalServerError());

            assertEquals(1, logs.count("pay.request.unexpected_failed"));
            assertEquals(1, logs.count("pay.request.complete"));
            assertTrue(logs.allText().contains("errorCode=INTERNAL_ERROR"));
            assertTrue(logs.allText().contains("exceptionType=IllegalStateException"));
            assertFalse(logs.allText().contains(SENSITIVE_VALUES));
        }
    }

    @RestController
    @RequestMapping("/synthetic")
    static class SyntheticPayController {

        @GetMapping("/normal")
        ResponseEntity<String> normal() {
            return ResponseEntity.ok("ok");
        }

        @PostMapping("/validation")
        ResponseEntity<String> validation(@Valid @RequestBody SyntheticRequest request) {
            return ResponseEntity.ok(request.value());
        }

        @GetMapping("/pay-exception")
        ResponseEntity<String> payException() {
            throw new PayException(
                    PayErrorCode.STATE_CONFLICT,
                    "synthetic business failure",
                    "PROCESSING",
                    "RETRY");
        }

        @GetMapping("/unexpected")
        ResponseEntity<String> unexpected() {
            throw new IllegalStateException(SENSITIVE_VALUES);
        }

        record SyntheticRequest(@NotBlank String value) {
        }
    }
}
