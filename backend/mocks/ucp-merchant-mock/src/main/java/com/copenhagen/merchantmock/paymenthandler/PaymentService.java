package com.copenhagen.merchantmock.paymenthandler;

import java.time.Clock;
import java.time.OffsetDateTime;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.checkout.CheckoutRepository;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.Audit;
import com.copenhagen.merchantmock.support.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 체크아웃 하나에 결제를 청구하고 결과를 행에 남긴다(Jira 179, DESIGN.md 8.5절).
 *
 * <p>순서는 셋이다. {@code PROCESSING} 을 조건부 UPDATE 로 찍고, Pay 를 부르고, 결과를 조건부 UPDATE 로
 * 반영한다(7.2절). 첫 UPDATE 가 0행이면 이 체크아웃에 이미 결제가 있다는 뜻이다. 그건 176 의 멱등 계층이
 * 막아야 했던 재진입이라 조용히 넘기지 않고 예외로 올린다.
 *
 * <p>Complete(176)가 5번 검증을 끝낸 뒤 이 메서드를 부른다. 그 배선이 붙기 전까지는 테스트가 직접 부른다.
 */
@Service
public class PaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

    private final CheckoutRepository repository;
    private final PayClient client;
    private final MerchantProperties merchant;
    private final PayProperties properties;
    private final Clock clock;

    public PaymentService(CheckoutRepository repository, PayClient client, MerchantProperties merchant,
            PayProperties properties, Clock clock) {
        this.merchant = merchant;
        this.repository = repository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    /** 청구 입력. {@code checkoutHash} 는 6.3절 다이제스트를 부르는 쪽이 계산해 넘긴다. */
    public record Charge(
            String merchantId,
            String checkoutId,
            String checkoutHash,
            long amountMinor,
            String currency,
            String credentialId) {
    }

    /**
     * 결제를 청구한다. 예외를 던지지 않고 돌아오면 {@code payment_status} 는 결과의 status 와 같다.
     *
     * @throws IllegalStateException 이미 결제가 시작된 체크아웃에 다시 청구했을 때. 부르는 쪽의 버그다
     */
    public PaymentResult charge(Charge charge) {
        LogContext.put(LogContext.CHECKOUT_ID, charge.checkoutId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        // credentialId 와 첫 재조회 시각을 청구 직전에 같이 적는다. 응답이 유실되면 이 행만 보고
        // 재POST 본문을 되살려야 한다(183, DESIGN.md 8.8절).
        if (!repository.beginPayment(charge.checkoutId(), now, charge.credentialId(),
                now.plus(properties.recheckInitialDelay()))) {
            throw new IllegalStateException(
                    "이미 결제가 시작된 체크아웃에 다시 청구하려 했다: " + charge.checkoutId());
        }
        Audit.event("payment.started").after(PaymentStatus.PROCESSING).log();

        PaymentRequest request = new PaymentRequest(charge.merchantId(), charge.checkoutId(), charge.checkoutHash(),
                charge.amountMinor(), charge.currency(), charge.credentialId());
        PaymentResult result;
        try {
            result = client.charge(request);
        } catch (RuntimeException exception) {
            // 클라이언트는 예외를 내지 않기로 약속했다(PayClient). 깨지더라도 PROCESSING 으로 영원히 남는 행은
            // 만들지 않는다. 처리됐는지 모르므로 UNKNOWN 으로 닫고 버그는 그대로 올린다.
            finish(charge.checkoutId(), PaymentResult.unknown(HttpPayClient.ERROR_UNREACHABLE));
            throw exception;
        }
        finish(charge.checkoutId(), result);
        return result;
    }

    private void finish(String checkoutId, PaymentResult result) {
        if (!repository.finishPayment(checkoutId, result.status(), result.paymentId())) {
            throw new IllegalStateException(
                    "결제 결과를 반영할 PROCESSING 행이 없다: " + checkoutId + " (" + result.status() + ")");
        }
        LogContext.put(LogContext.PAYMENT_ID, result.paymentId());
        Audit.event("payment.finished").before(PaymentStatus.PROCESSING).after(result.status())
                .reason(result.errorCode()).log();
        if (result.status() == PaymentStatus.UNKNOWN) {
            LOG.warn("결제 결과 불명. 재청구하지 않고 재조회 워커에 맡긴다. checkoutId={} paymentId={} errorCode={}",
                    checkoutId, result.paymentId(), result.errorCode());
        }
    }
}
