package com.copenhagen.merchantmock.checkout;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.CancelResult;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CheckoutRequest;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CompleteRequest;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CompleteResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout 생성·조회·수정·취소·완료. 전체 구매 19단계 중 6·13단계다(DESIGN.md 3.3절).
 *
 * <p>경로의 {@code merchantId} 가 어느 몰인지 말한다(393). 다른 몰의 체크아웃은 없는 것으로 답한다.
 *
 * <p>Complete 의 {@code Idempotency-Key} 는 받지만 쓰지 않는다. {@code checkout-complete-{checkoutId}} 로 id 에서
 * 나오므로 같은 키 재요청은 곧 같은 체크아웃의 재요청이고 상태가 멱등 기록이다(8.1절).
 */
@RestController
@RequestMapping(path = "/api/ucp/v1/{merchantId}/checkout-sessions", produces = MediaType.APPLICATION_JSON_VALUE)
public class CheckoutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CheckoutService service;
    private final CheckoutCompletionService completion;

    public CheckoutController(CheckoutService service, CheckoutCompletionService completion) {
        this.service = service;
        this.completion = completion;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Checkout> create(
            @PathVariable String merchantId,
            @RequestBody CheckoutRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(merchantId, request, idempotencyKey));
    }

    @GetMapping(path = "/{checkoutId}")
    public Checkout get(@PathVariable String merchantId, @PathVariable String checkoutId) {
        return service.get(merchantId, checkoutId);
    }

    @PutMapping(path = "/{checkoutId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Checkout update(@PathVariable String merchantId, @PathVariable String checkoutId,
            @RequestBody CheckoutRequest request) {
        return service.update(merchantId, checkoutId, request);
    }

    /** 취소. 바디가 없고 응답은 {@code {id, status}} 둘뿐이다(docs/API_SPEC.md). */
    @PostMapping(path = "/{checkoutId}/cancel")
    public CancelResult cancel(@PathVariable String merchantId, @PathVariable String checkoutId) {
        return service.cancel(merchantId, checkoutId);
    }

    /** 완료. 세 갈래 다 200 이고 {@code status} 가 갈래를 말한다(DESIGN.md 7.1절). */
    @PostMapping(path = "/{checkoutId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompleteResult complete(@PathVariable String merchantId, @PathVariable String checkoutId,
            @RequestBody CompleteRequest request) {
        return completion.complete(merchantId, checkoutId, request.credentialId());
    }
}
