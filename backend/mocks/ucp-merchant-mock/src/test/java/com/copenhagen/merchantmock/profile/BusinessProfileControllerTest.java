package com.copenhagen.merchantmock.profile;

import com.copenhagen.merchantmock.security.SigningKeyProvider;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 필수 테스트 1번. Profile Discovery(DESIGN.md 12절).
 *
 * <p>DB 가 필요 없도록 standalone MockMvc 로 컨트롤러만 띄운다.
 */
class BusinessProfileControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MerchantProperties properties = MerchantPropertiesFixture.defaults();
        BusinessProfileService service = new BusinessProfileService(properties, new SigningKeyProvider(properties));
        this.mockMvc = MockMvcBuilders.standaloneSetup(new BusinessProfileController(service)).build();
    }

    @Test
    @DisplayName("well-known/ucp 는 Profile 을 돌려준다")
    void getProfile_always_returnsUcpProfile() throws Exception {
        // when & then
        mockMvc.perform(get("/api/ucp/v1/merchant-a/.well-known/ucp"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.ucp.version").value("2026-08-25"))
                .andExpect(jsonPath("$.ucp.services['dev.ucp.shopping'][0].transport").value("rest"))
                .andExpect(jsonPath("$.ucp.services['dev.ucp.shopping'][0].endpoint")
                        .value("http://ucp-merchant-mock:8080/api/ucp/v1/merchant-a"));
    }

    @Test
    @DisplayName("payment_handlers 가 snake_case 로 나간다")
    void getProfile_always_writesPaymentHandlersInSnakeCase() throws Exception {
        // when & then
        mockMvc.perform(get("/api/ucp/v1/merchant-a/.well-known/ucp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ucp.payment_handlers['com.copenhagen.pay'][0].id")
                        .value("copenhagen-pay-handler"))
                .andExpect(jsonPath("$.ucp.paymentHandlers").doesNotExist());
    }

    @Test
    @DisplayName("keys 에 공개키가 실리고 개인키 성분은 없다")
    void getProfile_always_exposesPublicKeyOnly() throws Exception {
        // when & then
        mockMvc.perform(get("/api/ucp/v1/merchant-a/.well-known/ucp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value("merchant-a-es256-1"))
                .andExpect(jsonPath("$.keys[0].crv").value("P-256"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }
}
