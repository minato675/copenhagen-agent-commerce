package com.copenhagen.merchantmock.checkout;

import java.time.Clock;
import java.time.OffsetDateTime;

import com.copenhagen.merchantmock.support.Audit;
import com.copenhagen.merchantmock.support.LogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료 스윕 워커(Jira 182). 시한이 지난 {@code ready_for_complete} 를 {@code canceled} 로 닫는다.
 *
 * <p>세 워커 중 첫째다(DESIGN.md 8.4절). 인스턴스가 하나뿐이라 분산 락을 두지 않고 조건부 UPDATE 로만 집는다.
 *
 * <p><b>이 워커는 1차 방어선이 아니다.</b> 주기가 30초라 만료 직후에도 잠시 {@code ready_for_complete} 로
 * 보인다. 그 사이 Complete 가 들어와도 결제가 나가지 않는 것은 Complete 자신이 {@code expires_at} 을
 * 조건부 UPDATE 에 넣기 때문이다(176). 여기는 방치된 행을 정리하는 청소 장치다(DESIGN.md 7.1절).
 *
 * <p>주기와 배치 크기는 설정에서 직접 읽는다. {@code @Scheduled} 가 바인딩된 레코드를 못 보기 때문에
 * {@link com.copenhagen.merchantmock.support.MerchantProperties} 를 거치지 않는다(DESIGN.md 3.4절).
 */
@Component
@ConditionalOnProperty(prefix = "merchant.checkout", name = "sweep-enabled",
        havingValue = "true", matchIfMissing = true)
public class CheckoutExpirySweeper {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutExpirySweeper.class);

    private final CheckoutRepository repository;
    private final Clock clock;
    private final int batchSize;

    public CheckoutExpirySweeper(
            CheckoutRepository repository,
            Clock clock,
            @Value("${merchant.checkout.sweep-batch-size:100}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("merchant.checkout.sweep-batch-size 는 1 이상이어야 한다");
        }
        this.repository = repository;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${merchant.checkout.sweep-interval:PT30S}",
            initialDelayString = "${merchant.checkout.sweep-interval:PT30S}")
    public void sweep() {
        try (LogContext.Scope scope = LogContext.openWorker("sweep")) {
            int closed = repository.expireReady(OffsetDateTime.now(clock), batchSize);
            if (closed > 0) {
                LOG.info("만료 체크아웃 {}건을 canceled 로 닫았다", closed);
                Audit.event("checkout.expired").after(CheckoutStatus.CANCELED.apiValue()).reason("ttl_elapsed")
                        .with("count", closed).log();
            }
        }
    }
}
