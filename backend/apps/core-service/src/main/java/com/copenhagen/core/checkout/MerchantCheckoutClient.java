package com.copenhagen.core.checkout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.copenhagen.core.checkout.MerchantCheckoutMessages.Checkout;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CheckoutRequest;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CompleteRequest;
import com.copenhagen.core.checkout.MerchantCheckoutMessages.CompleteResult;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.common.merchant.MerchantRestClients;
import com.copenhagen.core.product.MerchantsProperties;
import com.copenhagen.core.ucp.MerchantProfileDirectory;

/**
 * Merchant Checkout(UCP {@code /ucp/v1/checkout-sessions}) 전용 경계. merchant 하나당 인스턴스 하나다.
 *
 * <p>생성 멱등은 merchant가 흡수한다 — {@code Idempotency-Key}에서 UUIDv5로 checkout id를 만들기 때문에
 * 같은 키의 재시도는 같은 체크아웃을 돌려받는다. Core는 키를 결정적으로 만들기만 하면 된다.
 */
public class MerchantCheckoutClient {

    static final String CHECKOUT_SESSIONS_PATH = "/ucp/v1/checkout-sessions";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(MerchantCheckoutClient.class);

    private final String merchantId;
    private final RestClient restClient;
    private final MerchantsProperties.MerchantConfig config;
    private final MerchantProfileDirectory directory;
    private final String platformProfileUrl;

    public MerchantCheckoutClient(String merchantId, RestClient restClient) {
        this.merchantId = merchantId;
        this.restClient = restClient;
        this.config = null;
        this.directory = null;
        this.platformProfileUrl = null;
    }

    public MerchantCheckoutClient(
            MerchantsProperties.MerchantConfig config, MerchantProfileDirectory directory, String platformProfileUrl) {
        this.merchantId = config.id();
        this.restClient = null;
        this.config = config;
        this.directory = directory;
        this.platformProfileUrl = platformProfileUrl;
    }

    public String merchantId() {
        return merchantId;
    }

    public Checkout create(CheckoutRequest request, String idempotencyKey) {
        try {
            return client().post()
                    .uri(directory == null ? CHECKOUT_SESSIONS_PATH : "/checkout-sessions")
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(request)
                    .retrieve()
                    .body(Checkout.class);
        } catch (HttpClientErrorException.NotFound exception) {
            // merchant는 없거나 단종된 상품에만 404를 준다. 계산서 자체를 만들 수 없는 경우다.
            throw new CoreException(CoreErrorCode.PRODUCT_NOT_FOUND);
        } catch (RestClientException exception) {
            log.warn("Merchant Checkout 생성 실패: merchantId={}", merchantId, exception);
            throw new CoreException(
                    CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE,
                    CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE.defaultMessage(),
                    null,
                    CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE.defaultNextAction(),
                    exception);
        }
    }

    /**
     * Complete Checkout(이슈 410). 세 갈래(completed/ready_for_complete/complete_in_progress) 다
     * HTTP 200으로 온다 — merchant가 실패로 갈래를 나누지 않는다. 여기서 예외는 merchant 자체를
     * 부르지 못했을 때만 던진다.
     */
    public CompleteResult complete(String merchantCheckoutId, String credentialId, String idempotencyKey) {
        try {
            return client().post()
                    .uri(
                            (directory == null ? CHECKOUT_SESSIONS_PATH : "/checkout-sessions")
                                    + "/{checkoutId}/complete",
                            merchantCheckoutId)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(CompleteRequest.of(credentialId))
                    .retrieve()
                    .body(CompleteResult.class);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new CoreException(CoreErrorCode.PRODUCT_NOT_FOUND);
        } catch (RestClientException exception) {
            log.warn("Merchant Complete Checkout 실패: merchantId={}, checkoutId={}",
                    merchantId, merchantCheckoutId, exception);
            throw new CoreException(
                    CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE,
                    CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE.defaultMessage(),
                    null,
                    CoreErrorCode.MERCHANT_CHECKOUT_UNAVAILABLE.defaultNextAction(),
                    exception);
        }
    }

    /** 응답 유실·비동기 완료 복구. 읽기 요청이므로 결제를 다시 실행하지 않는다. */
    public CompleteResult current(String checkoutId) {
        return client().get().uri((directory == null ? CHECKOUT_SESSIONS_PATH : "/checkout-sessions")
                + "/{checkoutId}", checkoutId).retrieve().body(CompleteResult.class);
    }

    private RestClient client() {
        if (directory == null) {
            return restClient;
        }
        return MerchantRestClients.create(
                config, directory.endpoint(config, "dev.ucp.shopping.checkout"), platformProfileUrl);
    }
}
