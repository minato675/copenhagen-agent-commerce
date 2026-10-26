package com.copenhagen.core.product;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code optionId}가 없으면 필드 자체를 생략한다({@code null}이면 직렬화하지 않음). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetailRequestEcho(String productId, String merchantId, String optionId) {
}
