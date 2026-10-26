package com.copenhagen.core.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.auth.CoreAuthenticationFilter;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.error.ApiErrorIssue;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.common.error.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code CoreAuthenticationFilter} 제외 이유는 {@code CartControllerTest}와 같다. */
@WebMvcTest(
        controllers = OrderValidationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class OrderValidationControllerTest {

    private static final long USER_ID = 42L;
    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final UUID CART_ID = UUID.randomUUID();
    private static final UUID ADDRESS_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderValidationService orderValidationService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("검증에 성공하면 201과 validationId·checkouts를 반환한다")
    void validate_success_returns201() throws Exception {
        stubPrincipalUser();
        UUID validationId = UUID.randomUUID();
        UUID orderSnapshotId = UUID.randomUUID();
        when(orderValidationService.validate(eq(USER_ID), eq(CART_ID), eq(1), eq(ADDRESS_ID)))
                .thenReturn(new OrderValidationResult(validationId, List.of(
                        new OrderValidationResult.MerchantCheckoutView(
                                "merchant-a", "checkout-1", orderSnapshotId, "READY",
                                "2026-01-01T00:15:00Z",
                                new OrderValidationResult.Amount(10000L, 3000L, 13000L, "KRW")))));

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validationId").value(validationId.toString()))
                .andExpect(jsonPath("$.checkouts[0].merchantId").value("merchant-a"))
                .andExpect(jsonPath("$.checkouts[0].merchantCheckoutId").value("checkout-1"))
                .andExpect(jsonPath("$.checkouts[0].orderSnapshotId").value(orderSnapshotId.toString()))
                .andExpect(jsonPath("$.checkouts[0].status").value("READY"))
                .andExpect(jsonPath("$.checkouts[0].amount.total").value(13000));
    }

    @Test
    @DisplayName("검증 반려는 409와 대표 오류코드·항목 사유를 함께 내려준다")
    void validate_rejected_returns409WithIssues() throws Exception {
        stubPrincipalUser();
        when(orderValidationService.validate(anyLong(), any(), anyInt(), any()))
                .thenThrow(new CoreException(
                        CoreErrorCode.ORDER_STOCK_INSUFFICIENT,
                        CoreErrorCode.ORDER_STOCK_INSUFFICIENT.defaultMessage(),
                        "incomplete",
                        CoreErrorCode.ORDER_STOCK_INSUFFICIENT.defaultNextAction(),
                        List.of(new ApiErrorIssue(
                                "merchant-a", "CHECKOUT_STOCK_INSUFFICIENT", "line_items[0]", "p-1",
                                "재고가 부족하다"))));

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.currentState").value("incomplete"))
                .andExpect(jsonPath("$.nextAction").value("RETURN_TO_CART"))
                .andExpect(jsonPath("$.issues[0].merchantId").value("merchant-a"))
                .andExpect(jsonPath("$.issues[0].code").value("CHECKOUT_STOCK_INSUFFICIENT"))
                .andExpect(jsonPath("$.issues[0].productId").value("p-1"));
    }

    @Test
    @DisplayName("사유가 없는 오류 응답에는 issues 키가 나타나지 않는다")
    void validate_errorWithoutIssues_omitsIssuesKey() throws Exception {
        stubPrincipalUser();
        when(orderValidationService.validate(anyLong(), any(), anyInt(), any()))
                .thenThrow(new CoreException(CoreErrorCode.CART_VERSION_CONFLICT));

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CART_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.issues").doesNotExist());
    }

    @Test
    @DisplayName("필수 필드가 없으면 400 INVALID_REQUEST다")
    void validate_missingField_returns400() throws Exception {
        stubPrincipalUser();

        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":1,"addressId":"%s"}
                                """.formatted(ADDRESS_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("principal이 없으면 401 AUTH_TOKEN_MISSING이다")
    void validate_withoutPrincipal_returns401() throws Exception {
        mockMvc.perform(post("/api/core/v1/orders/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_MISSING"));
    }

    private void stubPrincipalUser() {
        when(userRepository.findByPublicId(PRINCIPAL)).thenReturn(Optional.of(user()));
    }

    private MockHttpServletRequest withPrincipal(MockHttpServletRequest request) {
        CoreRequestContext.setPrincipal(request, PRINCIPAL);
        return request;
    }

    private static String requestBody() {
        return """
                {"cartId":"%s","cartVersion":1,"addressId":"%s"}
                """.formatted(CART_ID, ADDRESS_ID);
    }

    private static User user() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new User(
                USER_ID, PRINCIPAL, "홍길동", UserStatus.ACTIVE,
                EncryptedPii.of(new byte[] {1}), "email-hash", true,
                null, null, null, null, null, now, now);
    }
}
