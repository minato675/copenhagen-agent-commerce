package com.copenhagen.core.address;

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
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.common.error.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code CoreAuthenticationFilter} 제외 이유는 {@code UserProfileControllerTest}와 같다. */
@WebMvcTest(
        controllers = AddressController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CoreAuthenticationFilter.class))
@Import(GlobalExceptionHandler.class)
class AddressControllerTest {

    private static final long USER_ID = 42L;
    private static final UUID PRINCIPAL = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddressService addressService;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("등록 요청이 유효하면 201과 addressId를 반환한다")
    void register_valid_returns201() throws Exception {
        stubPrincipalUser();
        UUID addressId = UUID.randomUUID();
        when(addressService.register(eq(USER_ID), eq("홍길동"), eq("010-1234-5678"), eq("12345"),
                eq("서울시"), eq("101동"), eq("문 앞"))).thenReturn(addressId);

        mockMvc.perform(post("/api/core/v1/addresses")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addressId").value(addressId.toString()));
    }

    @Test
    @DisplayName("필수 필드가 비어 있으면 400 INVALID_REQUEST다")
    void register_blankRequiredField_returns400() throws Exception {
        stubPrincipalUser();

        mockMvc.perform(post("/api/core/v1/addresses")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"","recipientPhone":"010-1234-5678","postalCode":"12345",
                                 "addressLine1":"서울시"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("목록 조회는 서비스 결과를 그대로 배열로 반환한다")
    void list_returnsAddresses() throws Exception {
        stubPrincipalUser();
        UUID addressId = UUID.randomUUID();
        when(addressService.list(USER_ID)).thenReturn(List.of(
                new AddressResult(addressId, "홍길동", "010-1234-5678", "12345", "서울시", "101동", "문 앞", true)));

        mockMvc.perform(get("/api/core/v1/addresses").with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].addressId").value(addressId.toString()))
                .andExpect(jsonPath("$.items[0].recipient").exists())
                .andExpect(jsonPath("$.items[0].phone").exists())
                .andExpect(jsonPath("$.items[0].address").exists())
                .andExpect(jsonPath("$.items[0].detail").exists())
                .andExpect(jsonPath("$.items[0].memo").exists())
                .andExpect(jsonPath("$.items[0].isDefault").value(true));
    }

    @Test
    @DisplayName("존재하지 않는 배송지를 수정하면 404 ADDRESS_NOT_FOUND다")
    void update_missingAddress_returns404() throws Exception {
        stubPrincipalUser();
        UUID addressId = UUID.randomUUID();
        when(addressService.update(eq(USER_ID), eq(addressId), any(), any(), any(), any(), any(), any()))
                .thenThrow(new CoreException(CoreErrorCode.ADDRESS_NOT_FOUND));

        mockMvc.perform(put("/api/core/v1/addresses/" + addressId)
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
    }

    @Test
    @DisplayName("삭제에 성공하면 204를 반환한다")
    void delete_success_returns204() throws Exception {
        stubPrincipalUser();
        UUID addressId = UUID.randomUUID();

        mockMvc.perform(delete("/api/core/v1/addresses/" + addressId).with(this::withPrincipal))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("기본 지정에 성공하면 204를 반환한다")
    void setDefault_success_returns204() throws Exception {
        stubPrincipalUser();
        UUID addressId = UUID.randomUUID();

        mockMvc.perform(patch("/api/core/v1/addresses/" + addressId + "/default").with(this::withPrincipal))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("필터를 거치지 않아 principal이 없으면 401 AUTH_TOKEN_MISSING을 반환한다")
    void list_missingPrincipal_returns401() throws Exception {
        mockMvc.perform(get("/api/core/v1/addresses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_TOKEN_MISSING"));
    }

    @Test
    void get_returnsApkAddressResponse() throws Exception {
        stubPrincipalUser();
        UUID addressId = UUID.randomUUID();
        when(addressService.get(USER_ID, addressId)).thenReturn(
                new AddressResult(addressId, "recipient", "010-1234-5678", "12345", "address", null, null, true));

        mockMvc.perform(get("/api/core/v1/addresses/" + addressId).with(this::withPrincipal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressId").value(addressId.toString()))
                .andExpect(jsonPath("$.recipient").value("recipient"))
                .andExpect(jsonPath("$.phone").value("010-1234-5678"))
                .andExpect(jsonPath("$.address").value("address"))
                .andExpect(jsonPath("$.detail").value(""))
                .andExpect(jsonPath("$.memo").value(""));
    }

    @Test
    void register_acceptsApkAddressRequestFields() throws Exception {
        stubPrincipalUser();
        UUID addressId = UUID.randomUUID();
        when(addressService.register(eq(USER_ID), eq("recipient"), eq("010-1234-5678"), eq("12345"),
                eq("address"), eq("detail"), eq("memo"))).thenReturn(addressId);

        mockMvc.perform(post("/api/core/v1/addresses")
                        .with(this::withPrincipal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipient":"recipient","phone":"010-1234-5678","postalCode":"12345",
                                 "address":"address","detail":"detail","memo":"memo"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.addressId").value(addressId.toString()));
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
                {"recipientName":"홍길동","recipientPhone":"010-1234-5678","postalCode":"12345",
                 "addressLine1":"서울시","addressLine2":"101동","deliveryRequest":"문 앞"}
                """;
    }

    private static User user() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new User(
                USER_ID, PRINCIPAL, "홍길동", UserStatus.ACTIVE,
                EncryptedPii.of(new byte[] {1}), "email-hash", true,
                null, null, null, null, null, now, now);
    }
}
