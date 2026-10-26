package com.copenhagen.core.cart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.product.MerchantsProperties;

/**
 * 설정의 merchant id(문자열) ↔ {@code merchants.id}(BIGINT) 매핑.
 *
 * <p>{@code cart_items.merchant_id}는 {@code merchants} FK인데 상품 조회(순서 24)는 merchant를 설정
 * 문자열로만 다룬다. 둘을 잇는 키가 설정의 {@code services.merchants[].ref-id}이고, 그 행은
 * {@code V7__seed_merchants.sql}이 고정 PK로 넣는다.
 */
@Component
public class CartMerchants {

    private final Map<String, Long> refIdByMerchantId;
    private final Map<Long, String> merchantIdByRefId;

    public CartMerchants(MerchantsProperties properties) {
        Map<String, Long> refIds = new LinkedHashMap<>();
        Map<Long, String> merchantIds = new LinkedHashMap<>();
        List<MerchantsProperties.MerchantConfig> merchants =
                properties.merchants() == null ? List.of() : properties.merchants();
        for (MerchantsProperties.MerchantConfig config : merchants) {
            if (config.refId() == null) {
                continue;
            }
            refIds.put(config.id(), config.refId());
            merchantIds.put(config.refId(), config.id());
        }
        this.refIdByMerchantId = Map.copyOf(refIds);
        this.merchantIdByRefId = Map.copyOf(merchantIds);
    }

    public long requireRefId(String merchantId) {
        Long refId = refIdByMerchantId.get(merchantId);
        if (refId == null) {
            throw new CoreException(
                    CoreErrorCode.INVALID_REQUEST, "등록되지 않은 merchantId입니다.", null, "CHECK_REQUEST");
        }
        return refId;
    }

    /**
     * 저장된 행을 응답용 문자열 id로 되돌린다. 설정에서 빠진 merchant의 항목이 남아 있을 수 있어
     * (설정 변경·이전 배포) 그때는 숫자를 그대로 노출한다 — 응답을 비우는 것보다 추적에 낫다.
     */
    public String merchantId(long refId) {
        return merchantIdByRefId.getOrDefault(refId, String.valueOf(refId));
    }
}
