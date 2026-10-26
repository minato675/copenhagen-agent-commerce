package com.copenhagen.merchantmock.checkout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.copenhagen.merchantmock.catalog.CatalogFixture;
import com.copenhagen.merchantmock.catalog.CatalogStore;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CheckoutRequest;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.scenario.ScenarioState;
import com.copenhagen.merchantmock.security.MerchantAuthorizationSigner;
import com.copenhagen.merchantmock.security.SigningKeyProvider;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.TestSigningKeys;
import com.nimbusds.jose.jwk.ECKey;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 체크아웃 테스트가 같이 쓰는 조립 코드와 요청 샘플. */
final class CheckoutTestSupport {

    /** 2026-09-10 13:00:00 KST. ready_for_complete 면 expires_at 은 13:05:00 이다. */
    static final Instant NOW = Instant.parse("2026-09-10T04:00:00Z");
    static final String EXPECTED_EXPIRES_AT = "2026-09-10T13:05:00+09:00";

    static final CheckoutRequest.Destination SEOUL = new CheckoutRequest.Destination(
            "홍길동", "06236", "서울특별시 강남구 테헤란로 1", "101호");

    /** Core 의 PurchaseRequest id. 없으면 incomplete 라(DESIGN.md 6.4절) 기본 요청에 항상 실린다. */
    static final String PURCHASE_REQUEST_ID = "pr-0001";

    private CheckoutTestSupport() {
    }

    /**
     * @param key 서명 키. 응답의 merchant_authorization 을 이 키의 공개 절반으로 검증한다
     */
    record Harness(CheckoutService service, InMemoryCheckoutRepository repository, ScenarioState scenario,
            ObjectMapper objectMapper, ECKey key, Clock clock, MerchantProperties properties) {
    }

    static Harness harness() {
        return harness(Scenario.NORMAL);
    }

    /** 기본 배송비만 바꾼 하네스. 실제 설정은 0 이고 도서산간에서만 받는다(427). */
    static Harness harnessWithFlatFee(long flatFee) {
        return harness(Scenario.NORMAL, flatFee);
    }

    static Harness harness(Scenario initial) {
        return harness(initial, null);
    }

    static Harness harness(Scenario initial, Long flatFee) {
        CatalogStore catalog = new CatalogStore(CatalogFixture::seed);
        catalog.load();
        ScenarioState scenario = new ScenarioState(initial);
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        ECKey key = TestSigningKeys.generate();
        MerchantProperties properties = TestSigningKeys.propertiesSigningWith(key);
        if (flatFee != null) {
            properties = withFlatFee(properties, flatFee);
        }
        SigningKeyProvider keys = new SigningKeyProvider(properties);
        CheckoutEvaluator evaluator = new CheckoutEvaluator(catalog, scenario, properties, clock);
        InMemoryCheckoutRepository repository = new InMemoryCheckoutRepository();
        ObjectMapper objectMapper = JsonMapper.builder().build();
        CheckoutService service = new CheckoutService(repository, evaluator,
                new MerchantAuthorizationSigner(keys, scenario), properties, objectMapper);
        return new Harness(service, repository, scenario, objectMapper, key, clock, properties);
    }

    /** 레코드라 복사 생성자가 없다. 배송비만 갈아 끼운 사본을 만든다. */
    private static MerchantProperties withFlatFee(MerchantProperties base, long flatFee) {
        return new MerchantProperties(base.malls(), base.ucpVersion(), base.currency(), base.publicBaseUrl(),
                base.catalog(), base.checkout(),
                new MerchantProperties.Fulfillment(flatFee, base.fulfillment().remoteAreas()),
                base.signing(), base.capabilities(), base.paymentHandler(), base.trustedPlatforms(),
                base.scenario(), base.webhook());
    }

    static CheckoutRequest.LineItemRequest line(String productId, String sku, int quantity) {
        return new CheckoutRequest.LineItemRequest(productId, sku == null ? null : List.of(sku), quantity);
    }

    static CheckoutRequest request(CheckoutRequest.Destination destination,
            CheckoutRequest.LineItemRequest... lines) {
        return requestFor(destination, PURCHASE_REQUEST_ID, lines);
    }

    /** purchase_request_id 를 직접 정하는 판. null 을 주면 incomplete 가 나온다(DESIGN.md 6.4절). */
    static CheckoutRequest requestFor(CheckoutRequest.Destination destination, String purchaseRequestId,
            CheckoutRequest.LineItemRequest... lines) {
        CheckoutRequest.Fulfillment fulfillment = destination == null
                ? null : new CheckoutRequest.Fulfillment("shipping", destination);
        return new CheckoutRequest(List.of(lines), fulfillment, purchaseRequestId);
    }

    /** 러닝화 270 한 개를 서울로. 조건이 다 차 있어 ready_for_complete 가 나오는 기본 요청. */
    static CheckoutRequest runningShoesToSeoul() {
        return request(SEOUL, line("product-101", "product-101-270", 1));
    }
}
