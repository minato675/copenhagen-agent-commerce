package com.copenhagen.merchantmock.profile;

import java.util.List;
import java.util.Map;

import com.copenhagen.merchantmock.security.SigningKeyProvider;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.MerchantPropertiesFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 필수 테스트 1·2·3번의 단위 수준 검증(DESIGN.md 12절).
 *
 * <p>1 Profile Discovery, 2 Payment Handler 광고, 3 AP2-like Capability 광고.
 */
class BusinessProfileServiceTest {

    private static BusinessProfileResponse build(MerchantProperties properties) {
        return new BusinessProfileService(properties, new SigningKeyProvider(properties)).build("merchant-a");
    }

    @Test
    @DisplayName("버전과 REST 엔드포인트를 광고한다")
    void build_defaults_advertisesVersionAndRestEndpoint() {
        // given & when
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        // then
        assertEquals("2026-08-25", profile.ucp().version());
        List<BusinessProfileResponse.Service> shopping = profile.ucp().services().get("dev.ucp.shopping");
        assertEquals(1, shopping.size());
        assertEquals("rest", shopping.get(0).transport());
        assertEquals("http://ucp-merchant-mock:8080/api/ucp/v1/merchant-a", shopping.get(0).endpoint());
        assertEquals("2026-08-25", shopping.get(0).version());
    }

    @Test
    @DisplayName("Capability 다섯 개를 광고하고 Cart 는 광고하지 않는다")
    void build_defaults_advertisesFiveCapabilitiesWithoutCart() {
        // given & when
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        // then
        Map<String, List<BusinessProfileResponse.Capability>> capabilities = profile.ucp().capabilities();

        assertEquals(5, capabilities.size());
        assertTrue(capabilities.containsKey("dev.ucp.shopping.catalog.search"));
        assertTrue(capabilities.containsKey("dev.ucp.shopping.catalog.lookup"));
        assertTrue(capabilities.containsKey("dev.ucp.shopping.checkout"));
        assertTrue(capabilities.containsKey("dev.ucp.shopping.order"));
        assertTrue(capabilities.containsKey("dev.ucp.shopping.fulfillment"));
        assertFalse(capabilities.containsKey("dev.ucp.shopping.cart"), "Cart 는 광고하지 않기로 합의했다");
    }

    @Test
    @DisplayName("Payment Handler 를 광고한다")
    void build_defaults_advertisesPaymentHandler() {
        // given & when
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        // then
        List<BusinessProfileResponse.PaymentHandler> handlers =
                profile.ucp().paymentHandlers().get("com.copenhagen.pay");

        assertEquals(1, handlers.size());
        assertEquals("copenhagen-pay-handler", handlers.get(0).id());
        assertEquals("1", handlers.get(0).version());
    }

    @Test
    @DisplayName("AP2-like Capability 는 기본으로 꺼져 있다")
    void build_defaults_hidesAp2LikeCapability() {
        // given & when
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        // then
        assertFalse(profile.ucp().capabilities().containsKey("com.copenhagen.payment.ap2_like"));
    }

    @Test
    @DisplayName("AP2-like Capability 를 켜면 광고한다")
    void build_ap2LikeEnabled_advertisesAp2LikeCapability() {
        // given & when
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.withAp2LikeEnabled());

        // then
        assertTrue(profile.ucp().capabilities().containsKey("com.copenhagen.payment.ap2_like"));
        assertEquals(6, profile.ucp().capabilities().size());
    }

    @Test
    @DisplayName("keys 에 공개키가 실린다")
    void build_publicKeyConfigured_exposesKeyInKeys() {
        // given & when
        BusinessProfileResponse profile = build(MerchantPropertiesFixture.defaults());

        // then
        assertEquals(1, profile.keys().size());
        assertEquals("merchant-a-es256-1", profile.keys().get(0).get("kid"));
    }

    @Test
    @DisplayName("공개키 설정이 비어도 임시 키가 keys 에 실린다(DESIGN.md 3.5절)")
    void build_noPublicKey_advertisesEphemeralKey() {
        // given
        MerchantProperties properties = MerchantPropertiesFixture.withSigning(
                new MerchantProperties.Signing("merchant-a-es256-1", "ES256", "", "", ""));

        // when
        BusinessProfileResponse profile = build(properties);

        // then
        assertEquals(1, profile.keys().size());
        assertEquals("merchant-a-es256-1", profile.keys().get(0).get("kid"));
    }
}
