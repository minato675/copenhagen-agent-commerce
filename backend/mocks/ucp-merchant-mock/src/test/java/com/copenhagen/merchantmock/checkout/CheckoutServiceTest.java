package com.copenhagen.merchantmock.checkout;

import java.util.List;
import java.util.stream.Collectors;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CheckoutRequest;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Message;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Total;
import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.security.MerchantAuthorizationSigner;
import com.copenhagen.merchantmock.support.ApiException;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.EXPECTED_EXPIRES_AT;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.SEOUL;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.harness;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.line;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.request;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.requestFor;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.runningShoesToSeoul;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 체크아웃 생성·조회·수정과 INCOMPLETE 판정(Jira 172). DB 없이 시드와 인메모리 저장소로 돌린다.
 *
 * <p>Jira 172 완료 조건 셋이 여기 있다. 배송지 없이 만들면 incomplete + ADDRESS_REQUIRED,
 * 배송지를 PUT 하면 ready_for_complete, 가격 변경 시나리오에서 CHECKOUT_PRICE_CHANGED.
 */
class CheckoutServiceTest {

    private static List<String> codes(Checkout checkout) {
        return checkout.messages() == null
                ? List.of()
                : checkout.messages().stream().map(Message::code).collect(Collectors.toList());
    }

    private static long total(Checkout checkout, String type) {
        return checkout.totals().stream().filter(t -> type.equals(t.type())).mapToLong(Total::amount).sum();
    }

    @Test
    @DisplayName("동일 몰의 여러 상품과 수량에도 배송비는 주문서에 한 번만 부과한다")
    void create_multipleProducts_chargesShippingOnce() {
        Checkout checkout = harness().service().create("merchant-a",
                request(SEOUL, line("product-101", "product-101-270", 2),
                        line("product-104", "product-104-single", 3)), "shipping-once");

        assertEquals("ready_for_complete", checkout.status());
        assertEquals(259000L, total(checkout, "subtotal"));
        assertEquals(3000L, total(checkout, "fulfillment"));
        assertEquals(262000L, total(checkout, "total"));
        assertEquals(1L, checkout.totals().stream().filter(t -> "fulfillment".equals(t.type())).count());
    }

    @Test
    @DisplayName("무료배송 몰의 다품목 주문은 도서산간 추가금도 한 번만 부과한다")
    void create_multipleProducts_chargesRemoteSurchargeOnce() {
        CheckoutRequest.Destination jeju = new CheckoutRequest.Destination(
                "홍길동", "63000", "제주특별자치도 제주시 1", "101호");
        Checkout checkout = CheckoutTestSupport.harnessWithFlatFee(0L).service().create("merchant-a",
                request(jeju, line("product-101", "product-101-270", 2),
                        line("product-104", "product-104-single", 3)), "remote-once");

        assertEquals(3000L, total(checkout, "fulfillment"));
        assertEquals(262000L, total(checkout, "total"));
        assertEquals(List.of("CHECKOUT_REMOTE_AREA_SURCHARGE"), codes(checkout));
    }

    @Test
    @DisplayName("조건이 다 차면 ready_for_complete 이고 금액을 확정한다")
    void create_allConditionsMet_returnsReadyForCompleteWithTotals() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a", runningShoesToSeoul(), "checkout-create-1");

        // then
        assertEquals("ready_for_complete", checkout.status());
        assertEquals("KRW", checkout.currency());
        assertEquals(1, checkout.lineItems().size());
        assertEquals("line-1", checkout.lineItems().get(0).id());
        assertEquals("product-101", checkout.lineItems().get(0).item().id());
        assertEquals("러닝화 A", checkout.lineItems().get(0).item().title());
        assertEquals(89000L, checkout.lineItems().get(0).item().price());
        assertEquals("available", checkout.fulfillment().status());
        assertEquals(3000L, checkout.fulfillment().amount());
        assertEquals(89000L, total(checkout, "subtotal"));
        assertEquals(3000L, total(checkout, "fulfillment"));
        assertEquals(92000L, total(checkout, "total"));
        assertNull(checkout.messages(), "문제가 없으면 messages 키를 생략한다");
        assertEquals(EXPECTED_EXPIRES_AT, checkout.expiresAt());
    }

    @Test
    @DisplayName("ready_for_complete 면 Merchant Authorization 이 응답과 컬럼에 같은 값으로 실리고 내 공개키로 풀린다")
    void create_ready_signsAndStoresMerchantAuthorization() throws Exception {
        // given & when
        Harness h = harness();
        Checkout checkout = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-sign-1");

        // then
        String jws = checkout.merchantAuthorization();
        assertNotNull(jws);
        CheckoutSession row = h.repository().findById(checkout.id()).orElseThrow();
        assertEquals(jws, row.merchantAuthorizationJws(), "컬럼은 발급값 그대로다(불변 조건 6번)");
        assertTrue(row.canonicalCheckout().contains("\"merchant_authorization\":\"" + jws + "\""),
                "스냅샷에도 같은 문자열이 있어 조회가 재생한다");
        assertTrue(MerchantAuthorizationSigner.verify(jws, h.key().toPublicJWK()));

        JWTClaimsSet claims = SignedJWT.parse(jws).getJWTClaimsSet();
        assertEquals("merchant-a", claims.getStringClaim("merchantId"));
        assertEquals(checkout.id(), claims.getStringClaim("checkoutId"));
        assertEquals(CheckoutTestSupport.PURCHASE_REQUEST_ID, claims.getStringClaim("purchaseRequestId"));
        assertEquals(92000L, claims.getLongClaim("amountMinor"), "totals 의 total 이다");
        assertEquals("KRW", claims.getStringClaim("currency"));
        assertEquals(row.expiresAt().toInstant(), claims.getExpirationTime().toInstant(), "exp 는 expires_at 이다");
    }

    @Test
    @DisplayName("purchase_request_id 가 없으면 서명할 수 없으니 incomplete 다(6.4절)")
    void create_withoutPurchaseRequestId_isIncompleteAndUnsigned() {
        // given & when
        Harness h = harness();
        Checkout checkout = h.service().create("merchant-a",
                requestFor(SEOUL, null, line("product-101", "product-101-270", 1)), "checkout-create-sign-2");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_INCOMPLETE"), codes(checkout));
        assertEquals("purchase_request_id", checkout.messages().get(0).target());
        assertNull(checkout.merchantAuthorization());
        assertNull(checkout.expiresAt());
        assertNull(h.repository().findById(checkout.id()).orElseThrow().merchantAuthorizationJws());
    }

    @Test
    @DisplayName("incomplete 체크아웃에는 서명이 없다")
    void create_incomplete_hasNoMerchantAuthorization() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a",
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-sign-3");

        // then
        assertEquals("incomplete", checkout.status());
        assertNull(checkout.merchantAuthorization());
    }

    @Test
    @DisplayName("PUT 으로 금액이 바뀌면 새로 서명하고 claim 의 금액도 따라간다")
    void update_amountChanged_resignsWithNewAmount() throws Exception {
        // given
        Harness h = harness();
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-sign-4");

        // when
        Checkout updated = h.service().update("merchant-a", created.id(),
                request(SEOUL, line("product-101", "product-101-270", 2)));

        // then
        assertNotEquals(created.merchantAuthorization(), updated.merchantAuthorization());
        assertEquals(181000L, SignedJWT.parse(updated.merchantAuthorization()).getJWTClaimsSet()
                .getLongClaim("amountMinor"));
        assertEquals(updated.merchantAuthorization(),
                h.repository().findById(created.id()).orElseThrow().merchantAuthorizationJws());
    }

    @Test
    @DisplayName("PUT 으로 조건이 깨져 incomplete 로 내려가면 서명을 폐기한다(Jira 175)")
    void update_becomesIncomplete_dropsSignature() {
        // given
        Harness h = harness();
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-sign-5");
        assertNotNull(created.merchantAuthorization());

        // when
        Checkout updated = h.service().update("merchant-a", created.id(),
                request(null, line("product-101", "product-101-270", 1)));

        // then
        assertEquals("incomplete", updated.status());
        assertNull(updated.merchantAuthorization());
        assertNull(h.repository().findById(created.id()).orElseThrow().merchantAuthorizationJws());
    }

    @Test
    @DisplayName("시나리오 INVALID_MERCHANT_SIGNATURE 는 검증에 실패하는 서명을 싣는다")
    void create_invalidSignatureScenario_carriesUnverifiableJws() {
        // given & when
        Harness h = harness(Scenario.INVALID_MERCHANT_SIGNATURE);
        Checkout checkout = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-sign-6");

        // then
        assertEquals("ready_for_complete", checkout.status());
        assertNotNull(checkout.merchantAuthorization());
        assertTrue(!MerchantAuthorizationSigner.verify(checkout.merchantAuthorization(), h.key().toPublicJWK()));
    }

    @Test
    @DisplayName("배송지가 없으면 incomplete 와 ADDRESS_REQUIRED 다")
    void create_noAddress_returnsIncompleteWithAddressRequired() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a",
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-2");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("ADDRESS_REQUIRED"), codes(checkout));
        assertEquals("fulfillment.destination", checkout.messages().get(0).target());
        assertNull(checkout.fulfillment(), "배송지가 없으면 fulfillment 키를 생략한다");
        assertEquals(89000L, total(checkout, "total"), "배송비를 모르니 총액은 소계와 같다");
        assertNull(checkout.expiresAt(), "서명 전이라 만료 시각이 없다");
    }

    @Test
    @DisplayName("도서산간 우편번호면 배송비에 추가금이 붙고 warning 이 실린다")
    void create_remoteAreaPostalCode_addsSurchargeAndWarns() {
        // given
        CheckoutRequest.Destination jeju = new CheckoutRequest.Destination(
                "홍길동", "63000", "제주특별자치도 제주시 1", "101호");

        // when
        Checkout checkout = harness().service().create("merchant-a",
                request(jeju, line("product-101", "product-101-270", 1)), "checkout-create-remote-1");

        // then
        assertEquals("ready_for_complete", checkout.status(), "추가금은 막는 사유가 아니다");
        assertEquals(6000L, checkout.fulfillment().amount(), "기본 3000 + 제주 3000");
        assertEquals(6000L, total(checkout, "fulfillment"));
        assertEquals(95000L, total(checkout, "total"));
        assertEquals(List.of("CHECKOUT_REMOTE_AREA_SURCHARGE"), codes(checkout));
        assertEquals("warning", checkout.messages().get(0).severity());
        assertEquals("fulfillment.destination", checkout.messages().get(0).target(),
                "판정 입력이 우편번호만이 아니라 주소까지다");
        assertEquals(List.of("subtotal", "fulfillment", "total"),
                checkout.totals().stream().map(Total::type).collect(Collectors.toList()),
                "추가금을 totals 의 별도 줄로 내지 않는다");
    }

    @Test
    @DisplayName("구간마다 추가금이 다르고 먼저 걸리는 하나만 적용한다")
    void create_remoteIslandPostalCode_usesThatAreaSurchargeOnly() {
        // given
        CheckoutRequest.Destination ulleung = new CheckoutRequest.Destination(
                "홍길동", "40240", "경상북도 울릉군 1", null);

        // when
        Checkout checkout = harness().service().create("merchant-a",
                request(ulleung, line("product-101", "product-101-270", 1)), "checkout-create-remote-2");

        // then
        assertEquals(8000L, checkout.fulfillment().amount(), "기본 3000 + 도서산간 5000");
        assertEquals(97000L, total(checkout, "total"));
    }

    @Test
    @DisplayName("우편번호가 일반이어도 주소에 도서산간 낱말이 있으면 걸린다")
    void create_remoteAreaKeywordInAddress_addsSurcharge() {
        // given 우체국 기준은 우편번호가 아니라 군·면·섬 이름이다(ADR 0009)
        CheckoutRequest.Destination chuja = new CheckoutRequest.Destination(
                "홍길동", "63000", "제주특별자치도 제주시 추자면 대서리 1", null);

        // when
        Checkout checkout = harness().service().create("merchant-a",
                request(chuja, line("product-101", "product-101-270", 1)), "checkout-create-remote-5");

        // then
        assertEquals(8000L, checkout.fulfillment().amount(),
                "추자도는 제주 우편번호를 쓰지만 도서산간이다. 목록에서 먼저 걸리는 구간을 쓴다");
    }

    @Test
    @DisplayName("제외 낱말이 있으면 그 구간이 아니다")
    void create_excludedAreaInAddress_keepsFlatFee() {
        // given 우체국 목록의 "옹진군: 북도면, 영흥면을 제외한 전 지역"
        CheckoutRequest.Destination yeongheung = new CheckoutRequest.Destination(
                "홍길동", "23100", "인천광역시 옹진군 영흥면 1", null);

        // when
        Checkout checkout = harness().service().create("merchant-a",
                request(yeongheung, line("product-101", "product-101-270", 1)), "checkout-create-remote-6");

        // then
        assertEquals(3000L, checkout.fulfillment().amount());
        assertNull(checkout.messages());
    }

    @Test
    @DisplayName("기본 배송비가 0 이면 일반 지역은 무료이고 도서산간만 추가금을 받는다")
    void create_freeShippingConfig_chargesOnlyRemoteAreaSurcharge() {
        // given 실제 설정 모양이다. 기본 0, 도서산간만 받는다
        Harness free = CheckoutTestSupport.harnessWithFlatFee(0L);
        CheckoutRequest.Destination ulleung = new CheckoutRequest.Destination(
                "홍길동", "40240", "경상북도 울릉군 울릉읍 1", null);

        // when
        Checkout seoul = free.service().create("merchant-a",
                request(SEOUL, line("product-101", "product-101-270", 1)), "checkout-create-free-1");
        Checkout remote = free.service().create("merchant-a",
                request(ulleung, line("product-101", "product-101-270", 1)), "checkout-create-free-2");

        // then
        assertEquals(0L, seoul.fulfillment().amount(), "일반 지역은 무료 배송이다");
        assertEquals(89000L, total(seoul, "total"));
        assertEquals(5000L, remote.fulfillment().amount(), "도서산간은 추가금만 받는다");
        assertEquals(94000L, total(remote, "total"));
    }

    @Test
    @DisplayName("일반 지역과 우편번호 없는 배송지는 기본 배송비 그대로다")
    void create_nonRemoteOrMissingPostalCode_keepsFlatFee() {
        // given
        CheckoutRequest.Destination noPostalCode = new CheckoutRequest.Destination(
                "홍길동", null, "서울특별시 강남구 테헤란로 1", "101호");

        // when
        Checkout seoul = harness().service().create("merchant-a",
                request(SEOUL, line("product-101", "product-101-270", 1)), "checkout-create-remote-3");
        Checkout blank = harness().service().create("merchant-a",
                request(noPostalCode, line("product-101", "product-101-270", 1)), "checkout-create-remote-4");

        // then
        assertEquals(3000L, seoul.fulfillment().amount());
        assertEquals(3000L, blank.fulfillment().amount(), "판정할 수 없으면 일반 지역으로 본다");
        assertNull(blank.messages(), "추가금이 없으면 알릴 것도 없다");
    }

    @Test
    @DisplayName("옵션이 여럿인데 고르지 않으면 incomplete 다")
    void create_noOptionSelectedOnMultiVariant_returnsIncomplete() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a",
                request(SEOUL, line("product-101", null, 1)), "checkout-create-3");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_INCOMPLETE"), codes(checkout));
        assertEquals("line_items[0].option_value_ids", checkout.messages().get(0).target());
    }

    @Test
    @DisplayName("옵션이 하나뿐인 상품은 생략해도 된다")
    void create_singleVariantProduct_allowsOmittedOption() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a",
                request(SEOUL, line("product-104", null, 2)), "checkout-create-4");

        // then
        assertEquals("ready_for_complete", checkout.status());
        assertEquals(27000L * 2 + 3000L, total(checkout, "total"));
    }

    @Test
    @DisplayName("옵션 추가금이 단가에 붙는다")
    void create_variantWithPriceDelta_addsDeltaToUnitPrice() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a",
                request(SEOUL, line("product-102", "product-102-xl-black", 1)), "checkout-create-5");

        // then
        assertEquals(134000L, checkout.lineItems().get(0).item().price(), "129000 + XL 추가금 5000");
    }

    @Test
    @DisplayName("상품에 없는 옵션이면 incomplete 다")
    void create_unknownOptionValue_returnsIncomplete() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a",
                request(SEOUL, line("product-101", "product-102-m-black", 1)), "checkout-create-6");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_INCOMPLETE"), codes(checkout));
    }

    @Test
    @DisplayName("없는 상품이면 체크아웃을 만들지 않고 404 다")
    void create_unknownProduct_throwsProductNotFound() {
        // given & when
        Harness h = harness();

        // then
        ApiException thrown = assertThrows(ApiException.class, () -> h.service().create("merchant-a",
                request(SEOUL, line("product-999", null, 1)), "checkout-create-7"));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
        assertEquals(0, h.repository().size(), "계산서 자체를 만들지 않는다");
    }

    @Test
    @DisplayName("단종 상품도 404 다")
    void create_discontinuedProduct_throwsProductNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class, () -> harness().service().create("merchant-a",
                request(SEOUL, line("product-105", "product-105-navy", 1)), "checkout-create-8"));

        assertEquals("PRODUCT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    @DisplayName("재고가 0 인 옵션은 CHECKOUT_STOCK_INSUFFICIENT 다")
    void create_zeroStockVariant_returnsStockInsufficient() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a",
                request(SEOUL, line("product-101", "product-101-280", 1)), "checkout-create-9");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_STOCK_INSUFFICIENT"), codes(checkout));
    }

    @Test
    @DisplayName("수량이 재고를 넘으면 CHECKOUT_STOCK_INSUFFICIENT 다")
    void create_quantityOverStock_returnsStockInsufficient() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a",
                request(SEOUL, line("product-101", "product-101-270", 8)), "checkout-create-10");

        // then
        assertEquals(List.of("CHECKOUT_STOCK_INSUFFICIENT"), codes(checkout), "270 사이즈 재고는 7");
    }

    @Test
    @DisplayName("수량이 0 이면 incomplete 다")
    void create_zeroQuantity_returnsIncomplete() {
        // given & when
        Checkout checkout = harness().service().create("merchant-a",
                request(SEOUL, line("product-101", "product-101-270", 0)), "checkout-create-11");

        // then
        assertEquals("incomplete", checkout.status());
        assertTrue(codes(checkout).contains("CHECKOUT_INCOMPLETE"));
    }

    @Test
    @DisplayName("시나리오 OUT_OF_STOCK 은 재고가 있어도 품절이다")
    void create_outOfStockScenario_returnsStockInsufficient() {
        // given & when
        Checkout checkout = harness(Scenario.OUT_OF_STOCK).service().create("merchant-a",
                runningShoesToSeoul(), "checkout-create-12");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("CHECKOUT_STOCK_INSUFFICIENT"), codes(checkout));
    }

    @Test
    @DisplayName("시나리오 ADDRESS_NOT_SERVICEABLE 은 배송 불가다")
    void create_addressNotServiceableScenario_marksFulfillmentUnavailable() {
        // given & when
        Checkout checkout = harness(Scenario.ADDRESS_NOT_SERVICEABLE).service().create("merchant-a",
                runningShoesToSeoul(), "checkout-create-13");

        // then
        assertEquals("incomplete", checkout.status());
        assertEquals(List.of("ADDRESS_NOT_SERVICEABLE"), codes(checkout));
        assertEquals("unavailable", checkout.fulfillment().status());
        assertNull(checkout.fulfillment().amount(), "배송 못 하는데 배송비를 매기지 않는다");
        assertEquals(89000L, total(checkout, "total"));
    }

    @Test
    @DisplayName("시나리오 REQUIRES_ESCALATION 은 requires_escalation 이다")
    void create_requiresEscalationScenario_returnsRequiresEscalation() {
        // given & when
        Checkout checkout = harness(Scenario.REQUIRES_ESCALATION).service().create("merchant-a",
                runningShoesToSeoul(), "checkout-create-14");

        // then
        assertEquals("requires_escalation", checkout.status());
        assertEquals(List.of("MERCHANT_HANDOFF_REQUIRED"), codes(checkout));
        assertNull(checkout.expiresAt());
    }

    @Test
    @DisplayName("시나리오 PRICE_CHANGED 는 단가에 1,000원을 더한다")
    void create_priceChangedScenario_addsDeltaToUnitPrice() {
        // given & when
        Checkout checkout = harness(Scenario.PRICE_CHANGED).service().create("merchant-a",
                runningShoesToSeoul(), "checkout-create-15");

        // then
        assertEquals(90000L, checkout.lineItems().get(0).item().price());
        assertEquals("ready_for_complete", checkout.status(), "생성 시점에는 비교 대상이 없어 경고가 없다");
    }

    @Test
    @DisplayName("같은 멱등 키로 다시 만들면 같은 체크아웃을 돌려준다")
    void create_sameIdempotencyKey_returnsExistingCheckout() {
        // given & when
        Harness h = harness();

        Checkout first = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-16");
        Checkout second = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-16");

        // then
        assertEquals(first.id(), second.id());
        assertEquals(first, second);
        assertEquals(1, h.repository().size());
    }

    @Test
    @DisplayName("멱등 키가 다르면 다른 체크아웃이다")
    void create_differentIdempotencyKey_createsNewCheckout() {
        // given & when
        Harness h = harness();

        Checkout first = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-17");
        Checkout second = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-18");
        Checkout third = h.service().create("merchant-a", runningShoesToSeoul(), null);

        // then
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.id(), third.id());
        assertEquals(3, h.repository().size());
    }

    @Test
    @DisplayName("조회는 저장된 스냅샷을 그대로 돌려준다")
    void get_existingCheckout_returnsStoredSnapshot() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-19");

        Checkout fetched = h.service().get("merchant-a", created.id());

        // then
        assertEquals(created, fetched);
    }

    @Test
    @DisplayName("없는 체크아웃 조회는 CHECKOUT_NOT_FOUND 다")
    void get_unknownId_throwsCheckoutNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class, () -> harness().service().get("merchant-a", "nope"));

        assertEquals("CHECKOUT_NOT_FOUND", thrown.errorCode());
        assertEquals(404, thrown.status().value());
    }

    @Test
    @DisplayName("배송지를 PUT 하면 incomplete 가 ready_for_complete 로 간다")
    void update_addressAdded_movesToReadyForComplete() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create("merchant-a",
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-20");
        // then
        assertEquals("incomplete", created.status());

        Checkout updated = h.service().update("merchant-a", created.id(), runningShoesToSeoul());

        assertEquals("ready_for_complete", updated.status());
        assertNull(updated.messages());
        assertEquals(92000L, total(updated, "total"));
        assertEquals(EXPECTED_EXPIRES_AT, updated.expiresAt());
        assertEquals(1, h.repository().findById(created.id()).orElseThrow().version(), "조건부 UPDATE 로 version 이 오른다");
        assertEquals(updated, h.service().get("merchant-a", created.id()), "조회도 새 스냅샷을 준다");
    }

    @Test
    @DisplayName("PUT 에서 단가가 바뀌면 CHECKOUT_PRICE_CHANGED 경고를 얹고 200 이다")
    void update_unitPriceChanged_addsPriceChangedWarning() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-21");
        h.scenario().set(Scenario.PRICE_CHANGED);

        Checkout updated = h.service().update("merchant-a", created.id(), runningShoesToSeoul());

        // then
        assertEquals(List.of("CHECKOUT_PRICE_CHANGED"), codes(updated));
        assertEquals("warning", updated.messages().get(0).severity());
        assertEquals(90000L, updated.lineItems().get(0).item().price(), "새 가격이 담긴다");
        assertEquals("ready_for_complete", updated.status(), "경고는 완료를 막지 않는다. 재승인은 Core 와 Pay 의 몫");
    }

    @Test
    @DisplayName("PUT 은 전체 교체다")
    void update_newRequest_replacesEntireCheckout() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-22");

        Checkout updated = h.service().update("merchant-a", created.id(), request(SEOUL,
                line("product-104", null, 3), line("product-103", "product-103-black", 1)));

        // then
        assertEquals(2, updated.lineItems().size());
        assertEquals("product-104", updated.lineItems().get(0).item().id());
        assertEquals("product-103", updated.lineItems().get(1).item().id());
        assertEquals(27000L * 3 + 219000L + 3000L, total(updated, "total"));
    }

    @Test
    @DisplayName("생성하면 선택된 옵션이 line_skus_json 에 남는다")
    void create_withOptionValueIds_storesSelectedSku() {
        // given
        Harness h = harness();

        // when
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-skus-1");

        // then
        CheckoutSession row = h.repository().findById(created.id()).orElseThrow();
        assertEquals("{\"line-1\":\"product-101-270\"}", row.lineSkusJson(),
                "주문 상세가 이 값을 읽는다. 없으면 order_items.sku 를 채울 수 없다");
    }

    @Test
    @DisplayName("옵션을 바꿔 PUT 하면 저장된 SKU 도 바뀐다")
    void update_changedOption_replacesStoredSku() {
        // given
        Harness h = harness();
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-skus-2");

        // when
        h.service().update("merchant-a", created.id(), request(SEOUL, line("product-101", "product-101-260", 1)));

        // then
        CheckoutSession row = h.repository().findById(created.id()).orElseThrow();
        assertEquals("{\"line-1\":\"product-101-260\"}", row.lineSkusJson());
    }

    @Test
    @DisplayName("줄이 여럿이면 줄마다 SKU 가 남는다")
    void create_multipleLines_storesSkuPerLine() {
        // given
        Harness h = harness();

        // when
        Checkout created = h.service().create("merchant-a",
                request(SEOUL,
                        line("product-101", "product-101-270", 1),
                        line("product-103", "product-103-black", 2)),
                "checkout-create-skus-3");

        // then
        CheckoutSession row = h.repository().findById(created.id()).orElseThrow();
        assertEquals("{\"line-1\":\"product-101-270\",\"line-2\":\"product-103-black\"}",
                row.lineSkusJson());
    }

    @Test
    @DisplayName("completed 상태에서 PUT 하면 CHECKOUT_NOT_MODIFIABLE 이다")
    void update_completedCheckout_throwsNotModifiable() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-23");
        CheckoutSession row = h.repository().findById(created.id()).orElseThrow();
        h.repository().put(new CheckoutSession(row.id(), CheckoutStatus.COMPLETED, row.currency(),
                row.totalAmount(), row.fulfillmentAddressJson(), row.messagesJson(), row.lineSkusJson(),
                row.canonicalCheckout(), row.expiresAt(), row.version()));

        // then
        ApiException thrown = assertThrows(ApiException.class,
                () -> h.service().update("merchant-a", created.id(), runningShoesToSeoul()));

        assertEquals("CHECKOUT_NOT_MODIFIABLE", thrown.errorCode());
        assertEquals(409, thrown.status().value());
    }

    @Test
    @DisplayName("없는 체크아웃 PUT 은 CHECKOUT_NOT_FOUND 다")
    void update_unknownId_throwsCheckoutNotFound() {
        // when & then
        ApiException thrown = assertThrows(ApiException.class,
                () -> harness().service().update("merchant-a", "nope", runningShoesToSeoul()));

        assertEquals("CHECKOUT_NOT_FOUND", thrown.errorCode());
    }

    @Test
    @DisplayName("저장된 컬럼 캐시가 스냅샷과 맞는다")
    void create_always_keepsColumnCacheInSyncWithSnapshot() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-24");

        CheckoutSession row = h.repository().findById(created.id()).orElseThrow();

        // then
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, row.status());
        assertEquals("KRW", row.currency());
        assertEquals(92000L, row.totalAmount());
        assertNotNull(row.fulfillmentAddressJson());
        assertTrue(row.fulfillmentAddressJson().contains("\"postal_code\":\"06236\""), "배송지도 snake_case 로 저장");
        assertNull(row.messagesJson());
        assertEquals(EXPECTED_EXPIRES_AT,
                row.expiresAt().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        assertTrue(row.canonicalCheckout().contains("\"line_items\""), "스냅샷은 응답 JSON 그대로");
        assertTrue(row.canonicalCheckout().contains("\"expires_at\":\"" + EXPECTED_EXPIRES_AT + "\""));
    }

    @Test
    @DisplayName("스냅샷 JSON 에는 null 키가 없다")
    void create_always_omitsNullKeysInSnapshot() {
        // given & when
        Harness h = harness();
        Checkout created = h.service().create("merchant-a",
                request(null, line("product-101", "product-101-270", 1)), "checkout-create-25");

        String json = h.repository().findById(created.id()).orElseThrow().canonicalCheckout();

        // then
        assertTrue(json.contains("\"messages\""));
        assertTrue(!json.contains("\"fulfillment\""), "배송지가 없으면 fulfillment 키 자체가 없다");
        assertTrue(!json.contains("\"expires_at\""));
        assertTrue(!json.contains("null"), "값이 없으면 키를 생략한다(DESIGN.md 17절)");
    }

    @Test
    @DisplayName("다른 몰의 경로로는 체크아웃이 없는 것으로 답한다 (393)")
    void get_otherMall_isNotFound() {
        // given
        Harness h = harness();
        Checkout created = h.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-scope");

        // when & then: 같은 몰은 보이고 다른 몰은 404. 존재를 드러내지 않는다
        assertEquals(created.id(), h.service().get("merchant-a", created.id()).id());
        ApiException other = assertThrows(ApiException.class, () -> h.service().get("merchant-b", created.id()));
        assertEquals("CHECKOUT_NOT_FOUND", other.errorCode());
        assertThrows(ApiException.class, () -> h.service().update("merchant-b", created.id(), runningShoesToSeoul()));
        assertThrows(ApiException.class, () -> h.service().cancel("merchant-b", created.id()));

        // 모르는 몰은 체크아웃을 만들 수 없다
        ApiException unknown = assertThrows(ApiException.class,
                () -> h.service().create("merchant-z", runningShoesToSeoul(), "checkout-create-unknown"));
        assertEquals("MERCHANT_NOT_FOUND", unknown.errorCode());
        assertEquals("merchant-a", h.repository().findById(created.id()).orElseThrow().merchantId());
    }
}
