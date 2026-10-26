package com.copenhagen.merchantmock.paymenthandler;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.checkout.CheckoutRepository;
import com.copenhagen.merchantmock.checkout.PaymentRecheck;
import com.copenhagen.merchantmock.security.CheckoutDigest;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.Audit;
import com.copenhagen.merchantmock.support.LogContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 UNKNOWN 재조회 워커(Jira 183). Pay 응답이 유실돼 결과를 모르는 결제를 다시 물어본다.
 *
 * <p>세 워커 중 둘째다(DESIGN.md 8.4·8.8절). 인스턴스가 하나뿐이라 분산 락을 두지 않고 조건부 UPDATE 로만
 * 집는다. 집을 때 {@code payment_next_attempt_at} 을 리스만큼 밀어 같은 행을 두 번 묻지 않는다.
 *
 * <p><b>재청구가 아니라 조회다.</b> 불변 조건 2번이 재청구를 금지한다. {@code paymentId} 를 알면 GET 으로
 * 묻고, POST 응답 자체가 유실돼 모르면 <b>같은 멱등 키와 같은 값으로</b> 다시 POST 한다. Pay 의
 * {@code PayIdempotencyFilter} 가 그 요청을 컨트롤러에 들이지 않고 저장된 응답을 재생하므로 PG 는 다시
 * 불리지 않는다(DESIGN.md 19.5절).
 *
 * <p>{@link PaymentService#charge} 를 부르지 않고 {@link PayClient} 를 직접 부른다. 저쪽은 {@code beginPayment}
 * 로 재진입을 막게 돼 있어 두 번째 호출이 예외로 튕긴다. 재조회는 그 가드가 막으려던 재진입이 아니다.
 *
 * <p>확정된 결과는 {@link PaymentResolutionListener} 에 넘긴다. SUCCESS 면 저쪽이 주문을 만들고 체크아웃을
 * {@code completed} 로, FAILED 면 {@code ready_for_complete} 로 되돌린다(176).
 *
 * <p>한도를 넘긴 행은 상태를 바꾸지 않는다. {@code payment_status} 다섯 값에 "포기" 가 없고 그게 맞다 —
 * {@code UNKNOWN} 자체가 이미 사람이 확인해야 하는 상태다(8.3절). 옮길 곳이 없으니 스캔에서만 뺀다.
 */
@Component
@ConditionalOnProperty(prefix = "services.pay", name = "recheck-enabled",
        havingValue = "true", matchIfMissing = true)
public class PaymentRecheckWorker {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentRecheckWorker.class);

    private final CheckoutRepository repository;
    private final PayClient client;
    private final PaymentResolutionListener resolutions;
    private final MerchantProperties merchant;
    private final PayProperties properties;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;

    public PaymentRecheckWorker(
            CheckoutRepository repository,
            PayClient client,
            PaymentResolutionListener resolutions,
            MerchantProperties merchant,
            PayProperties properties,
            Clock clock,
            @Value("${services.pay.recheck-batch-size:100}") int batchSize,
            @Value("${services.pay.recheck-lease:PT2M}") Duration lease) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("services.pay.recheck-batch-size 는 1 이상이어야 한다");
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("services.pay.recheck-lease 는 0보다 커야 한다");
        }
        this.repository = repository;
        this.client = client;
        this.resolutions = resolutions;
        this.merchant = merchant;
        this.properties = properties;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${services.pay.recheck-interval:PT30S}",
            initialDelayString = "${services.pay.recheck-interval:PT30S}")
    public void recheck() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<PaymentRecheck> claimed = repository.claimUnknownDue(
                now, now.plus(lease), properties.recheckMaxAttempts(), batchSize);
        if (claimed.isEmpty()) {
            return;
        }
        LOG.info("결과를 모르는 결제 {}건을 다시 확인한다", claimed.size());
        for (PaymentRecheck row : claimed) {
            recheckOne(row, now);
        }
    }

    /** 한 건이 터져도 나머지를 계속 확인한다. 리스 덕분에 이 행은 다음 주기에 다시 집힌다. */
    private void recheckOne(PaymentRecheck row, OffsetDateTime now) {
        try (LogContext.Scope scope = LogContext.open(
                LogContext.REQUEST_ID, "recheck-" + UUID.randomUUID(),
                LogContext.CHECKOUT_ID, row.checkoutId(),
                LogContext.PAYMENT_ID, row.payPaymentId())) {
            apply(row, ask(row), now);
        } catch (RuntimeException exception) {
            LOG.error("결제 재조회가 예외로 끝났다. 다음 주기에 다시 집는다. checkoutId={} cause={}",
                    row.checkoutId(), exception.toString());
        }
    }

    /** 두 경로. {@code paymentId} 를 알면 GET, 모르면 같은 멱등 키로 재POST. */
    private PaymentResult ask(PaymentRecheck row) {
        if (row.payPaymentId() != null && !row.payPaymentId().isBlank()) {
            return client.lookup(row.merchantId(), row.payPaymentId());
        }
        if (!row.canRepost()) {
            // 청구 전에 죽었거나 175 이전에 만들어진 행이다. 물어볼 방법이 없다.
            LOG.error("재POST 본문을 되살릴 수 없다. paymentId 도 없어 확인할 방법이 없다. 사람이 봐야 한다. "
                    + "checkoutId={}", row.checkoutId());
            return PaymentResult.unknown(HttpPayClient.ERROR_UNREACHABLE);
        }
        return client.charge(new PaymentRequest(
                row.merchantId(),
                row.checkoutId(),
                CheckoutDigest.of(row.merchantAuthorizationJws()),
                row.totalAmount(),
                row.currency(),
                row.credentialId()));
    }

    private void apply(PaymentRecheck row, PaymentResult result, OffsetDateTime now) {
        if (isConflict(result)) {
            // 되살린 본문이 처음 보낸 것과 다르다. Pay 는 JCS 로 정규화한 뒤 해시하므로 키 순서나 공백
            // 때문일 수 없다. 값이 실제로 다르고 그건 내 버그다. FAILED 로 닫으면 사실이 아닌 것을 기록한다.
            LOG.error("재POST 본문이 처음 보낸 것과 다르다. UNKNOWN 을 유지한다. 6.3절 checkoutHash 와 "
                    + "8.8절 여섯 필드를 대조해야 한다. checkoutId={}", row.checkoutId());
            backOff(row, now);
            return;
        }
        if (result.status() == PaymentStatus.UNKNOWN || result.status() == PaymentStatus.PROCESSING) {
            backOff(row, now);
            return;
        }
        if (!repository.resolvePayment(row.checkoutId(), result.status(), result.paymentId())) {
            // 누가 먼저 확정했다. 재조회가 늦게 도착한 것이고 잘못된 상태는 아니다.
            LOG.info("확정할 UNKNOWN 행이 없다. 이미 정리됐다. checkoutId={}", row.checkoutId());
            return;
        }
        LOG.info("결제 결과를 확정했다. checkoutId={} status={} paymentId={} attempts={}",
                row.checkoutId(), result.status(), result.paymentId(), row.attemptCount());
        Audit.event("payment.resolved").before(PaymentStatus.UNKNOWN).after(result.status())
                .reason("recheck").with(LogContext.PAYMENT_ID, result.paymentId())
                .with("attempts", row.attemptCount()).log();
        // 주문 생성과 completed 전이는 Complete 쪽이 소유한다(DESIGN.md 8.8절). 여기서 예외가 나면 한 건
        // 처리 예외 경로로 잡힌다. 결제 상태는 이미 확정돼 다시 집히지 않으므로 그 로그가 유일한 단서다.
        String paymentId = result.paymentId() != null ? result.paymentId() : row.payPaymentId();
        resolutions.paymentResolved(row.checkoutId(), result.status(), paymentId);
    }

    private void backOff(PaymentRecheck row, OffsetDateTime now) {
        if (row.attemptCount() >= properties.recheckMaxAttempts()) {
            LOG.error("결제 결과를 {}회 확인하고도 모른다. 더 묻지 않는다. 사람이 Pay 와 대조해야 한다. "
                    + "checkoutId={} paymentId={}", row.attemptCount(), row.checkoutId(), row.payPaymentId());
            Audit.event("payment.recheck_exhausted").before(PaymentStatus.UNKNOWN).after(PaymentStatus.UNKNOWN)
                    .reason("max_attempts").with("attempts", row.attemptCount()).log();
            return;
        }
        repository.scheduleNextRecheck(row.checkoutId(),
                now.plus(properties.recheckBackoff(row.attemptCount())));
    }

    private static boolean isConflict(PaymentResult result) {
        return HttpPayClient.ERROR_IDEMPOTENCY_CONFLICT.equals(result.errorCode());
    }
}
