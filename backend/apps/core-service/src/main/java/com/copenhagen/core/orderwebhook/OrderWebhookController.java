package com.copenhagen.core.orderwebhook;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 서명 검증과 주문 반영이 완료된 Webhook에만 202를 응답한다. */
@RestController
public class OrderWebhookController {

    private final OrderWebhookService service;

    public OrderWebhookController(OrderWebhookService service) {
        this.service = service;
    }

    @PostMapping(path = "/webhooks/v1/ucp/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@RequestBody byte[] payload, @RequestHeader HttpHeaders headers) {
        service.receive(payload, headers);
    }
}
