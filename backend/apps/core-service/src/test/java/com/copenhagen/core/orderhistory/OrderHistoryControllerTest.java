package com.copenhagen.core.orderhistory;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.copenhagen.core.auth.user.User;
import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.auth.user.UserStatus;
import com.copenhagen.core.common.auth.CoreAuthenticationFilter;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.crypto.EncryptedPii;
import com.copenhagen.core.common.error.GlobalExceptionHandler;
import com.copenhagen.core.orderhistory.OrderHistoryService.AddressSnapshot;
import com.copenhagen.core.orderhistory.OrderHistoryService.ItemSummary;
import com.copenhagen.core.orderhistory.OrderHistoryService.OrderPageResponse;
import com.copenhagen.core.orderhistory.OrderHistoryService.OrderResponse;

@WebMvcTest(
        controllers = OrderHistoryController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class OrderHistoryControllerTest {

    private static final long USER_ID = 42L;
    private static final UUID PRINCIPAL = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderHistoryService service;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void list_returnsApkOrderShape() throws Exception {
        stubPrincipalUser();
        UUID orderId = UUID.randomUUID();
        when(service.list(USER_ID, 20, null, "CREATED")).thenReturn(new OrderPageResponse(
                List.of(order(orderId)), "next"));

        mockMvc.perform(get("/api/core/v1/orders")
                        .param("size", "20")
                        .param("status", "CREATED")
                        .with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.items[0].items[0].option").value("화이트"))
                .andExpect(jsonPath("$.items[0].address.recipient").value("홍길동"))
                .andExpect(jsonPath("$.items[0].paymentLabel").value("VISA **** 1234"))
                .andExpect(jsonPath("$.nextCursor").value("next"));
    }

    @Test
    void detail_returnsOrderForCurrentUser() throws Exception {
        stubPrincipalUser();
        UUID orderId = UUID.randomUUID();
        when(service.get(eq(USER_ID), eq(orderId))).thenReturn(order(orderId));

        mockMvc.perform(get("/api/core/v1/orders/" + orderId).with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    private static OrderResponse order(UUID orderId) {
        return new OrderResponse(
                orderId,
                "상품 A",
                "CREATED",
                87000L,
                "KRW",
                Instant.parse("2026-09-18T08:00:00Z"),
                List.of(new ItemSummary("상품 A", "화이트", 1)),
                new AddressSnapshot("홍길동", "01012345678", "06236", "서울", "101호", "문 앞"),
                "VISA **** 1234");
    }

    private void stubPrincipalUser() {
        when(userRepository.findByPublicId(PRINCIPAL)).thenReturn(Optional.of(user()));
    }

    private MockHttpServletRequest withPrincipal(MockHttpServletRequest request) {
        CoreRequestContext.setPrincipal(request, PRINCIPAL);
        return request;
    }

    private static User user() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new User(
                USER_ID, PRINCIPAL, "홍길동", UserStatus.ACTIVE,
                EncryptedPii.of(new byte[] {1}), "email-hash", true,
                null, null, null, null, null, now, now);
    }
}
