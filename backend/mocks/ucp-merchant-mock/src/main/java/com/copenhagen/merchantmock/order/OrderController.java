package com.copenhagen.merchantmock.order;

import com.copenhagen.merchantmock.order.OrderMessages.OrderView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 조회. Core 가 부르는 10개 중 마지막이다(DESIGN.md 3.3절).
 *
 * <p>생성 엔드포인트는 없다. 주문은 Complete 안에서만 생긴다. 176 이 {@link OrderService#create} 를 부른다.
 * 경로의 {@code merchantId} 와 주문의 몰이 다르면 404 다(393).
 */
@RestController
@RequestMapping(path = "/api/ucp/v1/{merchantId}/orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping(path = "/{orderId}")
    public OrderView get(@PathVariable String merchantId, @PathVariable String orderId) {
        return service.get(merchantId, orderId);
    }
}
