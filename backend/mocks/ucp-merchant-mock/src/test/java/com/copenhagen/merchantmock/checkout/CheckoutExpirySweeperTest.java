package com.copenhagen.merchantmock.checkout;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.NOW;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.harness;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.line;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.request;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.runningShoesToSeoul;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 만료 스윕 워커(Jira 182). 전이는 DESIGN.md 7.1절이 소유한다.
 *
 * <p>체크아웃은 NOW 시각에 만들어지고 ready_for_complete 면 expires_at 이 NOW + 5분이다.
 * 워커의 시계를 그보다 뒤로 돌려 만료를 만든다.
 */
class CheckoutExpirySweeperTest {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private static CheckoutExpirySweeper sweeperAt(Harness harness, Duration afterNow, int batchSize) {
        Clock clock = Clock.fixed(NOW.plus(afterNow), SEOUL_ZONE);
        return new CheckoutExpirySweeper(harness.repository(), clock, batchSize);
    }

    @Test
    @DisplayName("시한이 지난 ready_for_complete 를 canceled 로 닫는다")
    void sweep_expiredReadyCheckout_movesToCanceled() {
        // given & when
        Harness harness = harness();
        String id = harness.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-1").id();

        sweeperAt(harness, Duration.ofMinutes(6), 100).sweep();

        // then
        assertEquals(CheckoutStatus.CANCELED, harness.repository().findById(id).orElseThrow().status());
        assertEquals("canceled", harness.service().get("merchant-a", id).status());
    }

    @Test
    @DisplayName("아직 시한 안에 있으면 건드리지 않는다")
    void sweep_notYetExpired_leavesCheckoutUntouched() {
        // given & when
        Harness harness = harness();
        String id = harness.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-1").id();

        sweeperAt(harness, Duration.ofMinutes(4), 100).sweep();

        // then
        assertEquals(CheckoutStatus.READY_FOR_COMPLETE, harness.repository().findById(id).orElseThrow().status());
    }

    @Test
    @DisplayName("incomplete 는 시한이 없어 스윕 대상이 아니다")
    void sweep_incompleteCheckout_isNotSwept() {
        // given & when
        Harness harness = harness();
        String id = harness.service()
                .create("merchant-a", request(null, line("product-101", "product-101-270", 1)), "checkout-create-1")
                .id();

        sweeperAt(harness, Duration.ofDays(1), 100).sweep();

        // then
        assertEquals(CheckoutStatus.INCOMPLETE, harness.repository().findById(id).orElseThrow().status());
    }

    @Test
    @DisplayName("결제가 시작된 건은 집지 않는다")
    void sweep_completeInProgressCheckout_isNotSwept() {
        // given & when
        Harness harness = harness();
        String id = harness.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-1").id();
        CheckoutSession current = harness.repository().findById(id).orElseThrow();
        harness.repository().put(current.withStatus(CheckoutStatus.COMPLETE_IN_PROGRESS));

        sweeperAt(harness, Duration.ofMinutes(6), 100).sweep();

        // then
        assertEquals(CheckoutStatus.COMPLETE_IN_PROGRESS,
                harness.repository().findById(id).orElseThrow().status());
    }

    @Test
    @DisplayName("이미 취소된 건은 다시 닫지 않는다")
    void sweep_alreadyCanceledCheckout_isNotTouchedAgain() {
        // given & when
        Harness harness = harness();
        String id = harness.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-1").id();
        harness.service().cancel("merchant-a", id);
        long versionAfterCancel = harness.repository().findById(id).orElseThrow().version();

        sweeperAt(harness, Duration.ofMinutes(6), 100).sweep();

        // then
        assertEquals(versionAfterCancel, harness.repository().findById(id).orElseThrow().version());
    }

    @Test
    @DisplayName("배치 크기보다 많으면 나눠 닫는다")
    void sweep_moreThanBatchSize_closesInBatches() {
        // given & when
        Harness harness = harness();
        for (int i = 1; i <= 3; i++) {
            harness.service().create("merchant-a", runningShoesToSeoul(), "checkout-create-" + i);
        }

        CheckoutExpirySweeper sweeper = sweeperAt(harness, Duration.ofMinutes(6), 2);
        sweeper.sweep();

        // then
        assertEquals(1, readyCount(harness));
        sweeper.sweep();
        assertEquals(0, readyCount(harness));
    }

    @Test
    @DisplayName("배치 크기가 0 이하면 기동에서 막는다")
    void constructor_nonPositiveBatchSize_throwsOnStartup() {
        // given & when
        Harness harness = harness();
        Clock clock = Clock.fixed(NOW, SEOUL_ZONE);

        // then
        assertThrows(IllegalArgumentException.class,
                () -> new CheckoutExpirySweeper(harness.repository(), clock, 0));
    }

    /** 시드 셋 중 아직 ready_for_complete 로 남은 건수. */
    private static int readyCount(Harness harness) {
        int count = 0;
        for (int i = 1; i <= 3; i++) {
            String id = CheckoutIds.fromIdempotencyKey("checkout-create-" + i);
            if (harness.repository().findById(id).orElseThrow().status() == CheckoutStatus.READY_FOR_COMPLETE) {
                count++;
            }
        }
        return count;
    }
}
