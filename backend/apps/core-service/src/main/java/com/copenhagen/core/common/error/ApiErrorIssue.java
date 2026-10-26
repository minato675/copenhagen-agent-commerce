package com.copenhagen.core.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 오류 응답에 실리는 항목 단위 사유. 하나의 요청이 여러 항목 때문에 실패할 때, 어느 항목이 왜 막혔는지를
 * 사용자에게 돌려주기 위한 것이다(순서 27, ORDER-03 "문제 항목과 원인을 안내한다").
 *
 * <p>값이 없는 필드는 키를 생략한다. {@code merchantId}·{@code productId}는 주문 검증처럼 대상이 있는
 * 오류에서만 채워진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorIssue(
        String merchantId,
        String code,
        String target,
        String productId,
        String message) {
}
