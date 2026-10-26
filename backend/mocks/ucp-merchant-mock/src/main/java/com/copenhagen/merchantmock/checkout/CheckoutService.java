package com.copenhagen.merchantmock.checkout;

import java.util.Optional;

import com.copenhagen.contract.internalapi.PaymentStatus;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CancelResult;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CheckoutRequest;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.CompleteResult;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.PaymentRef;
import com.copenhagen.merchantmock.security.MerchantAuthorizationSigner;
import com.copenhagen.merchantmock.support.ApiException;
import com.copenhagen.merchantmock.support.Audit;
import com.copenhagen.merchantmock.support.LogContext;
import com.copenhagen.merchantmock.support.MerchantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 체크아웃 생성·조회·수정·취소(Jira 172·182)와 서명(175). 판정은 {@link CheckoutEvaluator}, 저장은
 * {@link CheckoutRepository}, 도장은 {@link MerchantAuthorizationSigner}.
 *
 * <p>생성 멱등은 id 로 흡수한다. Idempotency-Key 에서 UUIDv5 를 만들어 id 로 쓰고 INSERT 가 PK 에 걸리면
 * 기존 행을 돌려준다(DESIGN.md 8.1절). 수정은 전체 교체라 본래 멱등이다.
 *
 * <p>판정이 {@code ready_for_complete} 면 그 자리에서 서명한다(6.4절). 판정기가 {@code purchase_request_id} 없는
 * 요청을 {@code incomplete} 로 내리므로 여기 오는 ready 는 항상 서명할 수 있다. 재계산(PUT)은 새 내용이라 새로
 * 서명하고, 조건이 깨져 {@code incomplete} 로 내려가면 컬럼의 서명도 같이 비운다.
 */
@Service
public class CheckoutService {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutService.class);

    private final CheckoutRepository repository;
    private final CheckoutEvaluator evaluator;
    private final MerchantAuthorizationSigner signer;
    private final MerchantProperties merchant;
    private final ObjectMapper objectMapper;

    public CheckoutService(CheckoutRepository repository, CheckoutEvaluator evaluator,
            MerchantAuthorizationSigner signer, MerchantProperties merchant, ObjectMapper objectMapper) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.signer = signer;
        this.merchant = merchant;
        this.objectMapper = objectMapper;
    }

    public Checkout create(String merchantId, CheckoutRequest request, String idempotencyKey) {
        merchant.requireMall(merchantId);
        String id = CheckoutIds.fromIdempotencyKey(idempotencyKey);
        LogContext.put(LogContext.CHECKOUT_ID, id);
        Optional<CheckoutSession> existing = repository.findById(id);
        if (existing.isPresent()) {
            LOG.info("체크아웃 생성 재시도. 기존 행 반환. id={}", id);
            return snapshotOf(inMall(existing.get(), merchantId));
        }

        CheckoutEvaluator.Evaluation evaluation = evaluator.evaluate(merchantId, id, request, Optional.empty());
        Checkout checkout = signIfReady(merchantId, id, request, evaluation);
        CheckoutSession session = toSession(id, merchantId, request, evaluation, checkout, 0L);
        if (!repository.insert(session)) {
            LOG.info("체크아웃 생성 경합. 기존 행 반환. id={}", id);
            return repository.findById(id).map(found -> snapshotOf(inMall(found, merchantId)))
                    .orElseThrow(() -> ApiException.checkoutNotFound(id));
        }
        LOG.info("체크아웃 생성. id={} status={}", id, evaluation.status());
        Audit.event("checkout.created").after(evaluation.status().apiValue()).log();
        return checkout;
    }

    public Checkout get(String merchantId, String checkoutId) {
        LogContext.put(LogContext.CHECKOUT_ID, checkoutId);
        return snapshotOf(find(merchantId, checkoutId));
    }

    public Checkout update(String merchantId, String checkoutId, CheckoutRequest request) {
        LogContext.put(LogContext.CHECKOUT_ID, checkoutId);
        CheckoutSession current = find(merchantId, checkoutId);
        if (!current.status().modifiable()) {
            throw ApiException.checkoutNotModifiable(checkoutId, current.status());
        }

        Checkout previous = snapshotOf(current);
        CheckoutEvaluator.Evaluation evaluation = evaluator.evaluate(merchantId, checkoutId, request,
                Optional.of(previous));
        Checkout checkout = signIfReady(merchantId, checkoutId, request, evaluation);
        CheckoutSession next = toSession(checkoutId, merchantId, request, evaluation, checkout, current.version());
        if (!repository.update(next, current.version())) {
            throw ApiException.checkoutNotModifiable(checkoutId, current.status());
        }
        LOG.info("체크아웃 수정. id={} {} -> {}", checkoutId, current.status(), evaluation.status());
        Audit.event("checkout.updated").before(current.status().apiValue()).after(evaluation.status().apiValue())
                .log();
        return checkout;
    }

    /**
     * {@code ready_for_complete} 면 Merchant Authorization 을 발급해 응답에 얹는다(DESIGN.md 6.4절).
     *
     * <p>claim 은 여섯이다. {@code amountMinor} 는 totals 의 total, {@code exp} 는 expires_at. 감사에는 kid 만
     * 남기고 JWS 원문은 남기지 않는다(18.10). 아니면 판정 결과를 그대로 돌려준다.
     */
    private Checkout signIfReady(String merchantId, String id, CheckoutRequest request,
            CheckoutEvaluator.Evaluation evaluation) {
        Checkout checkout = evaluation.checkout();
        if (evaluation.status() != CheckoutStatus.READY_FOR_COMPLETE) {
            return checkout;
        }
        String jws = signer.sign(new MerchantAuthorizationSigner.Claims(
                merchantId,
                id,
                request.purchaseRequestId(),
                checkout.totalAmount(),
                checkout.currency(),
                evaluation.expiresAt().toInstant()));
        LOG.info("Merchant Authorization 발급. id={} kid={} amountMinor={} exp={}", id, merchant.signing().kid(),
                checkout.totalAmount(), evaluation.expiresAt());
        Audit.event("checkout.signed").after(CheckoutStatus.READY_FOR_COMPLETE.apiValue())
                .with("kid", merchant.signing().kid()).log();
        return checkout.withMerchantAuthorization(jws);
    }

    /**
     * 취소(Jira 182). 이미 취소된 건은 오류가 아니라 재생이다(DESIGN.md 7.1절).
     *
     * <p>멱등 키가 {@code checkout-cancel-{checkoutId}} 로 id 에서 나오므로 같은 키의 재시도가 곧 재취소다.
     * 그것을 409 로 막으면 Core 가 재시도할 방법이 없어진다.
     */
    public CancelResult cancel(String merchantId, String checkoutId) {
        LogContext.put(LogContext.CHECKOUT_ID, checkoutId);
        CheckoutSession current = find(merchantId, checkoutId);
        if (current.status() == CheckoutStatus.CANCELED) {
            LOG.info("체크아웃 취소 재시도. 상태 재생. id={}", checkoutId);
            return canceled(checkoutId);
        }
        if (!current.status().modifiable()) {
            throw ApiException.checkoutNotModifiable(checkoutId, current.status());
        }
        if (!repository.cancel(checkoutId)) {
            return resolveCancelRace(checkoutId);
        }
        LOG.info("체크아웃 취소. id={} {} -> CANCELED", checkoutId, current.status());
        Audit.event("checkout.canceled").before(current.status().apiValue()).after(CheckoutStatus.CANCELED.apiValue())
                .reason("core_request").log();
        return canceled(checkoutId);
    }

    /**
     * 조건부 UPDATE 가 0행이면 읽은 뒤에 상태가 바뀐 것이다. 다시 읽어 판단한다.
     *
     * <p>다른 요청이 먼저 취소했으면 재생, Complete 가 집어 갔으면 409 다.
     */
    private CancelResult resolveCancelRace(String checkoutId) {
        CheckoutSession latest = repository.findById(checkoutId)
                .orElseThrow(() -> ApiException.checkoutNotFound(checkoutId));
        if (latest.status() == CheckoutStatus.CANCELED) {
            LOG.info("체크아웃 취소 경합. 이미 취소됨. id={}", checkoutId);
            return canceled(checkoutId);
        }
        throw ApiException.checkoutNotModifiable(checkoutId, latest.status());
    }

    private static CancelResult canceled(String checkoutId) {
        return new CancelResult(checkoutId, CheckoutStatus.CANCELED.apiValue());
    }

    /** 경로의 몰과 행의 몰이 다르면 없는 것으로 답한다(393, DESIGN.md 3.3절). 다른 몰의 존재를 드러내지 않는다. */
    private CheckoutSession find(String merchantId, String checkoutId) {
        merchant.requireMall(merchantId);
        return inMall(repository.findById(checkoutId)
                .orElseThrow(() -> ApiException.checkoutNotFound(checkoutId)), merchantId);
    }

    private static CheckoutSession inMall(CheckoutSession session, String merchantId) {
        if (!merchantId.equals(session.merchantId())) {
            throw ApiException.checkoutNotFound(session.id());
        }
        return session;
    }

    /** @param checkout 서명까지 얹은 응답. 이 JSON 이 그대로 {@code canonical_checkout} 이 된다. */
    private CheckoutSession toSession(String id, String merchantId, CheckoutRequest request,
            CheckoutEvaluator.Evaluation evaluation, Checkout checkout, long version) {
        long total = checkout.totalAmount();
        boolean hasAddress = request != null && request.fulfillment() != null
                && request.fulfillment().destination() != null;
        String address = hasAddress ? objectMapper.writeValueAsString(request.fulfillment().destination()) : null;
        String messages = checkout.messages() == null ? null : objectMapper.writeValueAsString(checkout.messages());
        String lineSkus = evaluation.lineSkus().isEmpty()
                ? null : objectMapper.writeValueAsString(evaluation.lineSkus());
        return new CheckoutSession(
                id,
                merchantId,
                evaluation.status(),
                checkout.currency(),
                total,
                address,
                messages,
                lineSkus,
                objectMapper.writeValueAsString(checkout),
                evaluation.expiresAt(),
                version).withMerchantAuthorizationJws(checkout.merchantAuthorization());
    }

    /**
     * 저장된 스냅샷이 응답이다. 다시 계산하지 않는다.
     *
     * <p>상태와 완료 결과만 얹는다. 취소·만료·완료는 status 컬럼만 바꾸고 스냅샷 문자열은 건드리지
     * 않으므로(DESIGN.md 7.1절) 나갈 때 현재 값으로 덮는다. {@code completed} 면 저장된 Complete 응답의
     * {@code order}·{@code payment} 를, {@code complete_in_progress} 면 {@code payment} 만 얹는다. Core 확정본 6.1 의
     * 응답 유실 복구가 이 경로다.
     */
    private Checkout snapshotOf(CheckoutSession session) {
        Checkout stored = objectMapper.readValue(session.canonicalCheckout(), Checkout.class);
        String live = session.status().apiValue();
        Checkout current = live.equals(stored.status()) ? stored : stored.withStatus(live);
        if (session.status() == CheckoutStatus.COMPLETED && session.completionResponseJson() != null) {
            CompleteResult completion = objectMapper.readValue(session.completionResponseJson(), CompleteResult.class);
            return current.withCompletion(completion.order(), completion.payment());
        }
        if (session.status() == CheckoutStatus.COMPLETE_IN_PROGRESS) {
            PaymentStatus shown = session.paymentStatus() == null ? PaymentStatus.PROCESSING : session.paymentStatus();
            return current.withCompletion(null, new PaymentRef(session.payPaymentId(), shown.name(), null));
        }
        return current;
    }
}
