package com.copenhagen.merchantmock.checkout;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CompleteResult;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.OrderRef;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.PaymentRef;
import com.copenhagen.merchantmock.order.OrderMessages.Created;
import com.copenhagen.merchantmock.order.OrderService;
import com.copenhagen.merchantmock.paymenthandler.PaymentResolutionListener;
import com.copenhagen.merchantmock.paymenthandler.PaymentResult;
import com.copenhagen.merchantmock.paymenthandler.PaymentService;
import com.copenhagen.merchantmock.scenario.Scenario;
import com.copenhagen.merchantmock.scenario.ScenarioState;
import com.copenhagen.merchantmock.security.CheckoutDigest;
import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.Audit;
import com.copenhagen.merchantmock.support.LogContext;
import com.copenhagen.merchantmock.support.MerchantProperties;
import com.copenhagen.merchantmock.support.Sleeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Checkout Complete(Jira 176·177, DESIGN.md 7.1절·6.5절). 여기서 돈이 움직인다.
 *
 * <p>순서는 3.2절이 고정한다. 검증 다섯 → {@code complete_in_progress} 조건부 UPDATE → Pay 청구 → 결과에 따라
 * 주문·{@code completed}(SUCCESS), {@code ready_for_complete} 되돌림(FAILED), 그대로(UNKNOWN). 검증에서 하나라도
 * 걸리면 Pay 를 부르지 않는다(불변 조건 9번).
 *
 * <p>멱등은 상태로 한다. {@code completed} 면 저장된 응답을 그대로, {@code complete_in_progress} 면 진행 중 응답을
 * 돌려주고 결제하지 않는다(불변 조건 4번). 멱등 키 {@code checkout-complete-{checkoutId}} 는 id 에서 나오므로 저장하지
 * 않는다(8.1절). FAILED 응답은 저장하지 않는다 — 재승인 뒤 새 Credential 로 다시 오면 새 청구다.
 *
 * <p>늦게 확정된 UNKNOWN 은 {@link PaymentResolutionListener} 로 받는다(8.8절). Complete 의 SUCCESS 갈래와 같은 코드다.
 */
@Service
public class CheckoutCompletionService implements PaymentResolutionListener {

    /** {@code payment_credential_id varchar(100)}. Pay 의 credentialId 는 UUID 라 실제로는 36자다. */
    static final int CREDENTIAL_ID_MAX_LENGTH = 100;

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutCompletionService.class);

    private final CheckoutRepository repository;
    private final PaymentService payments;
    private final OrderService orders;
    private final ObjectMapper objectMapper;
    private final MerchantProperties merchant;
    private final ScenarioState scenario;
    private final Sleeper sleeper;
    private final TransactionOperations transactions;
    private final Clock clock;

    @Autowired
    public CheckoutCompletionService(CheckoutRepository repository, PaymentService payments, OrderService orders,
            ObjectMapper objectMapper, MerchantProperties merchant, ScenarioState scenario,
            PlatformTransactionManager transactionManager, Clock clock) {
        this(repository, payments, orders, objectMapper, merchant, scenario, Sleeper.THREAD,
                new TransactionTemplate(transactionManager), clock);
    }

    /** 테스트 생성자. 잠들지 않는 {@link Sleeper} 와 트랜잭션 없는 {@link TransactionOperations} 를 받는다. */
    CheckoutCompletionService(CheckoutRepository repository, PaymentService payments, OrderService orders,
            ObjectMapper objectMapper, MerchantProperties merchant, ScenarioState scenario, Sleeper sleeper,
            TransactionOperations transactions, Clock clock) {
        this.repository = repository;
        this.payments = payments;
        this.orders = orders;
        this.objectMapper = objectMapper;
        this.merchant = merchant;
        this.scenario = scenario;
        this.sleeper = sleeper;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Complete 한 번. 세 갈래 응답 다 200 이다(DESIGN.md 7.1절).
     *
     * @throws ApiException 검증 다섯 중 하나에 걸렸을 때(6.5절). Pay 는 불리지 않았다
     */
    public CompleteResult complete(String merchantId, String checkoutId, String credentialId) {
        LogContext.put(LogContext.CHECKOUT_ID, checkoutId);
        requireCredentialId(credentialId);
        CheckoutSession session = find(checkoutId);
        if (!merchantId.equals(session.merchantId())) {
            // 다른 몰의 체크아웃은 없는 것으로 답한다(393). 존재를 드러내지 않는다.
            throw ApiException.checkoutNotFound(checkoutId);
        }

        Optional<CompleteResult> settled = settledResponse(session);
        if (settled.isPresent()) {
            return settled.get();
        }
        rejectUnlessReady(session);

        delayIf(Scenario.COMPLETE_TIMEOUT_BEFORE_PROCESS, checkoutId);

        if (!repository.beginComplete(checkoutId, OffsetDateTime.now(clock))) {
            return resolveBeginRace(checkoutId);
        }
        LOG.info("Complete 시작. id={} ready_for_complete -> COMPLETE_IN_PROGRESS", checkoutId);
        Audit.event("checkout.completing").before(CheckoutStatus.READY_FOR_COMPLETE.apiValue())
                .after(CheckoutStatus.COMPLETE_IN_PROGRESS.apiValue()).log();

        PaymentResult result = payments.charge(new PaymentService.Charge(
                session.merchantId(),
                checkoutId,
                CheckoutDigest.of(requireSigned(session)),
                session.totalAmount() == null ? 0L : session.totalAmount(),
                session.currency(),
                credentialId));

        return switch (result.status()) {
            case SUCCESS -> {
                CompleteResult completed = finishSuccess(session, result.paymentId());
                delayIf(Scenario.COMPLETE_TIMEOUT_AFTER_ORDER, checkoutId);
                yield completed;
            }
            case FAILED -> revert(checkoutId, result);
            default -> inProgress(checkoutId, result.status(), result.paymentId());
        };
    }

    /** 183 워커가 UNKNOWN 을 확정한 뒤 부른다(DESIGN.md 8.8절). 워커 스레드라 요청 컨텍스트가 없다. */
    @Override
    public void paymentResolved(String checkoutId, PaymentStatus status, String payPaymentId) {
        if (status == PaymentStatus.SUCCESS) {
            CheckoutSession session = find(checkoutId);
            if (session.status() == CheckoutStatus.COMPLETED) {
                LOG.info("늦은 SUCCESS 인데 이미 completed 다. 할 일이 없다. id={}", checkoutId);
                return;
            }
            finishSuccess(session, payPaymentId);
            return;
        }
        if (status == PaymentStatus.FAILED) {
            revert(checkoutId, new PaymentResult(PaymentStatus.FAILED, payPaymentId, null, "late_failed", null));
            return;
        }
        LOG.warn("확정이 아닌 상태를 받았다. 무시한다. id={} status={}", checkoutId, status);
    }

    /**
     * 주문 생성 + {@code completed} + 응답 저장. 한 트랜잭션이다. 주문은 있는데 체크아웃이 진행 중으로 남는
     * 상태를 만들지 않기 위해서다. {@code OrderService.create} 의 트랜잭션이 여기에 합류하고 웹훅 첫 발송은
     * 이 트랜잭션이 커밋된 뒤 나간다(8.6절).
     */
    private CompleteResult finishSuccess(CheckoutSession session, String paymentId) {
        return transactions.execute(status -> {
            Created order = orders.create(session, PaymentStatus.SUCCESS);
            CompleteResult response = new CompleteResult(
                    session.id(),
                    CheckoutStatus.COMPLETED.apiValue(),
                    new OrderRef(order.id(), order.permalinkUrl()),
                    new PaymentRef(paymentId, PaymentStatus.SUCCESS.name(), null));
            if (!repository.finishComplete(session.id(), objectMapper.writeValueAsString(response))) {
                // 사이에 누가 끝냈다. 주문은 UNIQUE 로 하나뿐이니 저장된 응답이 곧 답이다. 없으면 버그다.
                CompleteResult stored = settledResponse(find(session.id())).orElseThrow(
                        () -> new IllegalStateException(
                                "completed 로 옮길 COMPLETE_IN_PROGRESS 행이 없는데 저장된 응답도 없다: " + session.id()));
                LOG.info("Complete 종료 경합. 저장된 응답을 돌려준다. id={}", session.id());
                return stored;
            }
            LogContext.put(LogContext.ORDER_ID, order.id());
            LogContext.put(LogContext.PAYMENT_ID, paymentId);
            LOG.info("Complete 성공. id={} COMPLETE_IN_PROGRESS -> COMPLETED orderId={} paymentId={}",
                    session.id(), order.id(), paymentId);
            Audit.event("checkout.completed").before(CheckoutStatus.COMPLETE_IN_PROGRESS.apiValue())
                    .after(CheckoutStatus.COMPLETED.apiValue()).reason("payment_success")
                    .with(LogContext.ORDER_ID, order.id()).with(LogContext.PAYMENT_ID, paymentId).log();
            return response;
        });
    }

    private CompleteResult revert(String checkoutId, PaymentResult result) {
        if (!repository.revertComplete(checkoutId)) {
            LOG.warn("되돌릴 COMPLETE_IN_PROGRESS 행이 없다. 이미 정리됐다. id={}", checkoutId);
        } else {
            LOG.info("결제 실패. id={} COMPLETE_IN_PROGRESS -> READY_FOR_COMPLETE errorCode={}", checkoutId,
                    result.errorCode());
            Audit.event("checkout.complete_reverted").before(CheckoutStatus.COMPLETE_IN_PROGRESS.apiValue())
                    .after(CheckoutStatus.READY_FOR_COMPLETE.apiValue()).reason("payment_failed")
                    .with("errorCode", result.errorCode()).log();
        }
        return new CompleteResult(checkoutId, CheckoutStatus.READY_FOR_COMPLETE.apiValue(), null,
                new PaymentRef(result.paymentId(), PaymentStatus.FAILED.name(), result.errorCode()));
    }

    private static CompleteResult inProgress(String checkoutId, PaymentStatus paymentStatus, String paymentId) {
        PaymentStatus shown = paymentStatus == null ? PaymentStatus.PROCESSING : paymentStatus;
        return new CompleteResult(checkoutId, CheckoutStatus.COMPLETE_IN_PROGRESS.apiValue(), null,
                new PaymentRef(paymentId, shown.name(), null));
    }

    /** {@code completed}·{@code complete_in_progress} 면 결제하지 않고 응답을 재생한다(불변 조건 4번). */
    private Optional<CompleteResult> settledResponse(CheckoutSession session) {
        if (session.status() == CheckoutStatus.COMPLETED) {
            if (session.completionResponseJson() == null) {
                throw new IllegalStateException("completed 인데 저장된 응답이 없다: " + session.id());
            }
            LOG.info("Complete 재요청. 저장된 응답 재생. id={}", session.id());
            Audit.event("checkout.complete_replayed").before(session.status().apiValue())
                    .after(session.status().apiValue()).reason("idempotent_retry").log();
            return Optional.of(objectMapper.readValue(session.completionResponseJson(), CompleteResult.class));
        }
        if (session.status() == CheckoutStatus.COMPLETE_IN_PROGRESS) {
            LOG.info("Complete 재요청. 아직 진행 중이다. id={} payment_status={}", session.id(),
                    session.paymentStatus());
            Audit.event("checkout.complete_replayed").before(session.status().apiValue())
                    .after(session.status().apiValue()).reason("idempotent_retry").log();
            return Optional.of(inProgress(session.id(), session.paymentStatus(), session.payPaymentId()));
        }
        return Optional.empty();
    }

    /** 검증 다섯 중 상태·만료 둘(6.5절). 여기서 던지면 Pay 를 부르지 않은 것이다. */
    private void rejectUnlessReady(CheckoutSession session) {
        switch (session.status()) {
            case CANCELED -> throw ApiException.checkoutNotModifiable(session.id(), session.status());
            case INCOMPLETE, REQUIRES_ESCALATION -> throw ApiException.checkoutIncomplete(session.id(),
                    session.status());
            case READY_FOR_COMPLETE -> {
                if (session.expiresAt() == null || !session.expiresAt().isAfter(OffsetDateTime.now(clock))) {
                    throw ApiException.checkoutExpired(session.id());
                }
            }
            default -> throw new IllegalStateException("settledResponse 가 먼저 걸러야 했다: " + session.status());
        }
    }

    /** 조건부 UPDATE 가 0행이면 사이에 상태가 바뀐 것이다. 다시 읽어 판단한다(취소의 resolveCancelRace 와 같다). */
    private CompleteResult resolveBeginRace(String checkoutId) {
        CheckoutSession latest = find(checkoutId);
        Optional<CompleteResult> settled = settledResponse(latest);
        if (settled.isPresent()) {
            LOG.info("Complete 시작 경합. 다른 요청이 먼저 집었다. id={}", checkoutId);
            return settled.get();
        }
        rejectUnlessReady(latest);
        // ready 이고 만료도 아닌데 0행이면 UPDATE 와 SELECT 사이에 시한이 지난 것이다.
        throw ApiException.checkoutExpired(checkoutId);
    }

    private static String requireSigned(CheckoutSession session) {
        String jws = session.merchantAuthorizationJws();
        if (jws == null || jws.isBlank()) {
            // 175 부터 ready_for_complete 는 반드시 서명돼 있다(6.4절). 여기 오면 이전 배포의 행이거나 버그다.
            throw new IllegalStateException("ready_for_complete 인데 Merchant Authorization 이 없다: " + session.id());
        }
        return jws;
    }

    private static void requireCredentialId(String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            throw ApiException.invalidRequest("payment.instrument.credential.credentialId 가 필요하다");
        }
        if (credentialId.length() > CREDENTIAL_ID_MAX_LENGTH) {
            throw ApiException.invalidRequest("credentialId 는 " + CREDENTIAL_ID_MAX_LENGTH + "자를 넘을 수 없다");
        }
    }

    private CheckoutSession find(String checkoutId) {
        return repository.findById(checkoutId).orElseThrow(() -> ApiException.checkoutNotFound(checkoutId));
    }

    private void delayIf(Scenario target, String checkoutId) {
        if (!scenario.is(target)) {
            return;
        }
        Duration delay = merchant.checkout().scenarioDelay();
        LOG.info("시나리오 {}. {} 기다린다. id={}", target.name().toLowerCase(Locale.ROOT), delay, checkoutId);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("지연 대기가 끊겼다. 바로 진행한다. id={}", checkoutId);
        }
    }
}
