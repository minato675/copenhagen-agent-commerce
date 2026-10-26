package com.copenhagen.core.orderhistory;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.copenhagen.core.orderhistory.OrderHistoryService.ItemDetail;
import com.copenhagen.core.orderhistory.OrderHistoryService.OrderV2PageResponse;
import com.copenhagen.core.orderhistory.OrderHistoryService.OrderV2Response;

/**
 * v2 응답 계약을 실제 요청 경로로 확인한다. 항목 필드가 늘어난 API라 "명세에 없는 필드가 섞이지 않는가"와
 * "이미지가 없을 때 키가 사라지는가"가 핵심이다.
 */
@WebMvcTest(
        controllers = OrderHistoryV2Controller.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class OrderHistoryV2ControllerTest {

    private static final long USER_ID = 42L;
    private static final UUID PRINCIPAL = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderHistoryService service;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("항목이 식별자 3종과 이미지를 담고 필드가 7개를 넘지 않는다")
    void list_returnsItemDetailContract() throws Exception {
        stubPrincipalUser();
        UUID orderId = UUID.randomUUID();
        when(service.listV2(USER_ID, 20, null, "CONFIRMED"))
                .thenReturn(new OrderV2PageResponse(List.of(order(orderId, item())), "next"));

        mockMvc.perform(get("/api/core/v2/orders")
                        .param("size", "20")
                        .param("status", "CONFIRMED")
                        .with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.items[0].items[0].merchantId").value("merchant-a"))
                .andExpect(jsonPath("$.items[0].items[0].merchantProductId").value("P-1"))
                .andExpect(jsonPath("$.items[0].items[0].merchantVariantId").value("P-1-BLK"))
                .andExpect(jsonPath("$.items[0].items[0].imageUrl")
                        .value("https://cdn.example.test/p-1.jpg"))
                .andExpect(jsonPath("$.items[0].items[0].option").value("블랙 / M"))
                .andExpect(jsonPath("$.items[0].items[0].quantity").value(1))
                // 명세에 없는 필드가 섞이지 않는지 — 키 개수로 고정한다.
                .andExpect(jsonPath("$.items[0].items[0].length()").value(7))
                .andExpect(jsonPath("$.items[0].length()").value(9))
                .andExpect(jsonPath("$.nextCursor").value("next"))
                // 배송지는 v1과 같은 등급의 PII다. 키가 빠지거나 늘지 않아야 한다.
                .andExpect(jsonPath("$.items[0].address.length()").value(6));
    }

    @Test
    @DisplayName("이미지가 없으면 imageUrl 키 자체가 빠진다")
    void list_omitsImageUrlWhenAbsent() throws Exception {
        stubPrincipalUser();
        ItemDetail withoutImage =
                new ItemDetail("merchant-a", "P-1", "P-1-BLK", "맨투맨", "블랙 / M", null, 1);
        when(service.listV2(USER_ID, null, null, null))
                .thenReturn(new OrderV2PageResponse(List.of(order(UUID.randomUUID(), withoutImage)), null));

        mockMvc.perform(get("/api/core/v2/orders").with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].items[0].imageUrl").doesNotExist())
                .andExpect(jsonPath("$.items[0].items[0].length()").value(6))
                // 마지막 페이지에서도 nextCursor 키는 남기고 값만 비운다(v1과 같은 규칙).
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void detail_returnsOrderForCurrentUser() throws Exception {
        stubPrincipalUser();
        UUID orderId = UUID.randomUUID();
        when(service.getV2(eq(USER_ID), eq(orderId))).thenReturn(order(orderId, item()));

        mockMvc.perform(get("/api/core/v2/orders/" + orderId).with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].merchantProductId").value("P-1"));
    }

    @Test
    @DisplayName("지원하지 않는 메서드는 405다 — catch-all에 걸려 500이 되지 않는다")
    void unsupportedMethod_returns405() throws Exception {
        mockMvc.perform(post("/api/core/v2/orders").with(this::withPrincipal))
                .andExpect(status().isMethodNotAllowed());
    }

    /**
     * 현재 동작을 그대로 고정한다. {@code MethodArgumentTypeMismatchException} 전용 핸들러가 없어
     * {@code GlobalExceptionHandler}의 catch-all로 떨어진다 — v1도 같아서 v2에서만 고치지 않는다.
     * 별도 이슈로 다룬다.
     */
    @Test
    @DisplayName("[알려진 격차] orderId가 UUID가 아니면 400이 아니라 500이다 — v1과 동일")
    void malformedOrderId_currentlyFallsToCatchAll() throws Exception {
        stubPrincipalUser();

        mockMvc.perform(get("/api/core/v2/orders/not-a-uuid").with(this::withPrincipal))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("응답에 인증 헤더나 쿠키가 섞이지 않는다")
    void response_carriesNoAuthHeaders() throws Exception {
        stubPrincipalUser();
        when(service.listV2(USER_ID, null, null, null))
                .thenReturn(new OrderV2PageResponse(List.of(), null));

        mockMvc.perform(get("/api/core/v2/orders").with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("Authorization"));
    }

    private static ItemDetail item() {
        return new ItemDetail("merchant-a", "P-1", "P-1-BLK", "맨투맨", "블랙 / M",
                "https://cdn.example.test/p-1.jpg", 1);
    }

    private static OrderV2Response order(UUID orderId, ItemDetail item) {
        return new OrderV2Response(
                orderId,
                "맨투맨",
                "CONFIRMED",
                87000L,
                "KRW",
                Instant.parse("2026-09-18T08:00:00Z"),
                List.of(item),
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
