package com.copenhagen.core.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.copenhagen.contract.internalapi.PaymentApprovalItem;
import com.copenhagen.contract.internalapi.PaymentApprovalStatusResponse;
import com.copenhagen.core.checkout.MerchantCheckoutRepository;
import com.copenhagen.core.checkout.MerchantCheckoutRepository.AwaitingApprovalCheckout;

/**
 * Pay 승인 세션 폴링(이슈 410). Front·Pay 양쪽 다 Core에 알리는 새 호출이 없어도 되도록, Core가 직접
 * 승인 상태를 확인해 확정 즉시 Merchant Complete Checkout으로 이어간다.
 *
 * <p>기본은 비활성화다({@code core.payment.worker.enabled=false}) — Pay·Merchant Mock이 없는 환경(단위
 * 테스트 등)에서 스케줄이 조용히 실패 로그를 쌓지 않게 한다. 배포 환경에서만 켠다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
        prefix = "core.payment.worker",
        name = "enabled",
        havingValue = "true")
public class PaymentApprovalPollingWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalPollingWorker.class);
    private static final String REASON_USER_REJECTED = "USER_REJECTED";

    private final MerchantCheckoutRepository merchantCheckoutRepository;
    private final PaymentApprovalClient paymentApprovalClient;
    private final PaymentCompletionService completionService;
    private final Clock clock;

    public PaymentApprovalPollingWorker(
            MerchantCheckoutRepository merchantCheckoutRepository,
            PaymentApprovalClient paymentApprovalClient,
            PaymentCompletionService completionService,
            Clock coreClock) {
        this.merchantCheckoutRepository = merchantCheckoutRepository;
        this.paymentApprovalClient = paymentApprovalClient;
        this.completionService = completionService;
        this.clock = coreClock;
    }

    @Scheduled(
            fixedDelayString = "${core.payment.worker.poll-interval-seconds:3}000",
            initialDelayString = "${core.payment.worker.poll-interval-seconds:3}000")
    public void poll() {
        for (Map.Entry<String, List<AwaitingApprovalCheckout>> group
                : groupByApproval(merchantCheckoutRepository.findCompleting(clock.instant())).entrySet()) {
            try {
                PaymentApprovalStatusResponse status = paymentApprovalClient.status(group.getKey());
                for (AwaitingApprovalCheckout row : group.getValue()) {
                    PaymentApprovalStatusResponse item = itemStatus(status, row);
                    if (item != null && merchantCheckoutRepository.beginCompleting(row.id(), clock.instant())) {
                        finish(row, completionService.recover(row, item));
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("payment.completion.retry.failed approvalId={}, error={}",
                        group.getKey(), exception.getClass().getSimpleName());
            }
        }
        for (Map.Entry<String, List<AwaitingApprovalCheckout>> group
                : groupByApproval(merchantCheckoutRepository.findAwaitingApproval()).entrySet()) {
            try {
                // 승인 1건이 판매처 N곳을 갖는다(이슈 S15P21A207-486). 행마다 부르면 같은 승인을 N번
                // 조회하게 되므로 승인 단위로 한 번만 부른다.
                PaymentApprovalStatusResponse status = paymentApprovalClient.status(group.getKey());
                for (AwaitingApprovalCheckout row : group.getValue()) {
                    pollOne(row, status);
                }
            } catch (RuntimeException exception) {
                log.warn("payment.approval.poll.failed approvalId={}", group.getKey(), exception);
            }
        }
    }

    private static Map<String, List<AwaitingApprovalCheckout>> groupByApproval(
            List<AwaitingApprovalCheckout> rows) {
        Map<String, List<AwaitingApprovalCheckout>> grouped = new LinkedHashMap<>();
        for (AwaitingApprovalCheckout row : rows) {
            if (row.paymentApprovalId() != null) {
                grouped.computeIfAbsent(row.paymentApprovalId(), key -> new ArrayList<>()).add(row);
            }
        }
        return grouped;
    }

    /**
     * 승인 상태에서 이 checkout의 결과만 떼어낸다. 승인·거부·만료는 세션 단위지만 credential과 결제는
     * 판매처별이므로 그 값들은 {@code items}에서 자기 몫을 찾아 쓴다.
     *
     * <p>자기 몫이 없으면 {@code null}이다. 구 계약(단일 판매처) 응답으로 폴백하지 않는다 — 다른
     * 판매처의 credential로 Complete Checkout을 부르면 엉뚱한 결제가 나간다.
     */
    private PaymentApprovalStatusResponse itemStatus(
            PaymentApprovalStatusResponse status, AwaitingApprovalCheckout row) {
        PaymentApprovalItem item = status.items().stream()
                .filter(candidate -> row.merchantCheckoutId().equals(candidate.checkoutId()))
                .findFirst()
                .orElse(null);
        if (item == null) {
            log.warn("payment.approval.item.missing approvalId={}, checkoutId={}",
                    status.approvalId(), row.merchantCheckoutId());
            return null;
        }
        return new PaymentApprovalStatusResponse(status.approvalId(), status.approvalStatus(), item.paymentId(),
                item.paymentStatus(), status.amountMinor(), status.currency(), item.credentialId(),
                item.credentialExpiresAt(), item.paymentLabel(), status.nextAction(), List.of(item));
    }

    private void pollOne(AwaitingApprovalCheckout row, PaymentApprovalStatusResponse sessionStatus) {
        Instant now = clock.instant();
        switch (sessionStatus.approvalStatus()) {
            // 승인·완료만 판매처별 credential이 필요하다. 나머지는 세션 단위 결론이라 그대로 적용한다.
            case APPROVED, COMPLETED -> {
                PaymentApprovalStatusResponse status = itemStatus(sessionStatus, row);
                if (status != null) {
                    handleApproved(row, status);
                }
            }
            case REJECTED -> merchantCheckoutRepository.markCancelled(row.id(), REASON_USER_REJECTED, now);
            case EXPIRED, INVALIDATED -> merchantCheckoutRepository.markExpired(row.id(), now);
            default -> {
                if (row.paymentApprovalExpiresAt() != null && !now.isBefore(row.paymentApprovalExpiresAt())) {
                    merchantCheckoutRepository.markExpired(row.id(), now);
                }
            }
        }
    }

    private void handleApproved(AwaitingApprovalCheckout row, PaymentApprovalStatusResponse status) {
        if (status.approvalStatus()
                == com.copenhagen.contract.internalapi.ApprovalStatus.COMPLETED) {
            if (merchantCheckoutRepository.beginCompleting(row.id(), clock.instant())) {
                finish(row, completionService.synchronize(row, status.paymentLabel()));
            }
            return;
        }
        String credentialId = status.credentialId();
        if (credentialId == null || credentialId.isBlank()) {
            // 승인은 됐지만 Payment Credential이 아직 발급 전이다 — 다음 tick에 다시 확인한다.
            return;
        }
        if (!merchantCheckoutRepository.beginCompleting(row.id(), clock.instant())) {
            // 다른 워커 인스턴스가 먼저 집었다.
            return;
        }

        PaymentCompletionService.Outcome outcome = completionService.complete(row, credentialId, status.paymentLabel());
        finish(row, outcome);
    }

    private void finish(AwaitingApprovalCheckout row, PaymentCompletionService.Outcome outcome) {
        Instant now = clock.instant();
        switch (outcome) {
            case COMPLETED -> merchantCheckoutRepository.markCompleted(row.id(), now);
            case FAILED -> merchantCheckoutRepository.markFailed(row.id(), "PAYMENT_FAILED", now);
            case EXPIRED -> merchantCheckoutRepository.markExpired(row.id(), now);
            case IN_PROGRESS -> {
                // Lease가 만료되면 다시 조회한다. 미확정 결과를 성공·실패로 바꾸지 않는다.
            }
        }
    }
}
