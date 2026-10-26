package com.copenhagen.core.product;

import java.util.List;

import com.copenhagen.contract.ucp.CatalogOptionValue;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.common.merchant.MerchantRestClients;
import com.copenhagen.core.ucp.MerchantProfileDirectory;

/**
 * Merchant Catalog(UCP {@code /ucp/v1/catalog/*}) 전용 경계. merchant 하나당 인스턴스 하나(자기 자신의
 * id·displayName을 알고 있어 {@link Offer} 조립에 그대로 쓴다).
 *
 * <p>여기서 주는 가격·재고는 탐색용 현재 정보다. 최종 거래 조건은 Checkout이 정한다(ADR-0004).
 * Core는 이 응답을 재계산하지 않고 그대로 전달한다.
 */
class MerchantCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(MerchantCatalogClient.class);

    private final String merchantId;
    private final String merchantName;
    private final String baseUrl;
    private final RestClient restClient;
    private final MerchantsProperties.MerchantConfig config;
    private final MerchantProfileDirectory directory;
    private final String platformProfileUrl;

    MerchantCatalogClient(String merchantId, String merchantName, String baseUrl, RestClient restClient) {
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.baseUrl = baseUrl;
        this.restClient = restClient;
        this.config = null;
        this.directory = null;
        this.platformProfileUrl = null;
    }

    MerchantCatalogClient(
            MerchantsProperties.MerchantConfig config, MerchantProfileDirectory directory, String platformProfileUrl) {
        this.merchantId = config.id();
        this.merchantName = config.displayName();
        this.baseUrl = config.baseUrl().toString();
        this.restClient = null;
        this.config = config;
        this.directory = directory;
        this.platformProfileUrl = platformProfileUrl;
    }

    String merchantId() {
        return merchantId;
    }

    String merchantName() {
        return merchantName;
    }

    /** 장바구니가 {@code product_url}(NOT NULL)을 합성할 때만 쓴다 — Catalog 응답에 상품 url이 없다. */
    String baseUrl() {
        return baseUrl;
    }

    List<CatalogItem> search(String query, Long maxPrice, String currency) {
        try {
            CatalogItemsResponse response = client("dev.ucp.shopping.catalog.search").post()
                    .uri(path("/catalog/search", "/ucp/v1/catalog/search"))
                    .body(new CatalogSearchRequest(query, new CatalogFilters(maxPrice, currency)))
                    .retrieve()
                    .body(CatalogItemsResponse.class);
            return response == null ? List.of() : response.items();
        } catch (RestClientException exception) {
            throw merchantUnavailable(exception);
        }
    }

    CatalogProductDetail product(String productId) {
        try {
            return client("dev.ucp.shopping.catalog.lookup").post()
                    .uri(path("/catalog/product", "/ucp/v1/catalog/product"))
                    .body(new CatalogProductRequest(productId))
                    .retrieve()
                    .body(CatalogProductDetail.class);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new CoreException(CoreErrorCode.PRODUCT_NOT_FOUND);
        } catch (RestClientException exception) {
            throw merchantUnavailable(exception);
        }
    }

    private CoreException merchantUnavailable(RestClientException exception) {
        log.warn("Merchant Catalog 호출 실패: merchantId={}", merchantId, exception);
        return new CoreException(
                CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE,
                CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE.defaultMessage(),
                null,
                CoreErrorCode.MERCHANT_CATALOG_UNAVAILABLE.defaultNextAction(),
                exception);
    }

    private RestClient client(String capability) {
        if (directory == null) {
            return restClient;
        }
        return MerchantRestClients.create(config, directory.endpoint(config, capability), platformProfileUrl);
    }

    private String path(String canonical, String legacy) {
        return directory == null ? legacy : canonical;
    }

    // Merchant 응답 계약을 그대로 옮긴 내부 전용 레코드. CatalogMessages(ucp-merchant-mock)와 필드가 1:1 대응한다.

    record CatalogSearchRequest(String query, CatalogFilters filters) {
    }

    record CatalogFilters(Long maxPrice, String currency) {
    }

    record CatalogProductRequest(String productId) {
    }

    record CatalogItemsResponse(List<CatalogItem> items) {
    }

    record CatalogItem(String productId, String title, CatalogPrice price, String availability, String imageUrl) {
        CatalogItem(String productId, String title, CatalogPrice price, String availability) {
            this(productId, title, price, availability, null);
        }
    }

    record CatalogPrice(long amount, String currency) {
    }

    record CatalogProductDetail(
            String productId,
            String title,
            String description,
            CatalogPrice price,
            List<CatalogOption> options,
            String availability,
            String imageUrl) {
        CatalogProductDetail(String productId, String title, String description, CatalogPrice price,
                List<CatalogOption> options, String availability) {
            this(productId, title, description, price, options, availability, null);
        }
    }

    record CatalogOption(String name, List<CatalogOptionValue> values) {
    }

}
