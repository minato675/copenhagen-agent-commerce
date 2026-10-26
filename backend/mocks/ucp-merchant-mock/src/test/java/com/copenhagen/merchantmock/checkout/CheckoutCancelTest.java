package com.copenhagen.merchantmock.checkout;

import com.copenhagen.merchantmock.checkout.CheckoutMessages.CancelResult;
import com.copenhagen.merchantmock.checkout.CheckoutMessages.Checkout;
import com.copenhagen.merchantmock.checkout.CheckoutTestSupport.Harness;
import com.copenhagen.merchantmock.support.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.harness;
import static com.copenhagen.merchantmock.checkout.CheckoutTestSupport.runningShoesToSeoul;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 체크아웃 취소(Jira 182). 전이는 DESIGN.md 7.1절이 소유한다.
 *
 * <p>Jira 완료 조건 중 "취소된 체크아웃은 GET 에서 canceled 로 응답한다" 가 여기 있다.
 * 스냅샷을 건드리지 않고 응답에서만 덮는 방식이라 그 두 가지를 같이 단언한다.
 */
class CheckoutCancelTest {

    private static String cancelableId(Harness harness, String key) {
        return harness.service().create("merchant-a", runningShoesToSeoul(), key).id();
    }

    @Test
    @DisplayName("취소하면 두 필드만 돌려준다")
    void cancel_cancelableCheckout_returnsIdAndStatusOnly() {
        // given & when
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");

        CancelResult result = harness.service().cancel("merchant-a", id);

        // then
        assertEquals(id, result.id());
        assertEquals("canceled", result.status());
    }

    @Test
    @DisplayName("취소된 체크아웃은 GET 에서 canceled 다")
    void get_canceledCheckout_returnsCanceledStatus() {
        // given & when
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        harness.service().cancel("merchant-a", id);

        // then
        assertEquals("canceled", harness.service().get("merchant-a", id).status());
    }

    @Test
    @DisplayName("취소해도 저장된 스냅샷 문자열은 그대로다")
    void cancel_always_keepsCanonicalCheckoutUnchanged() {
        // given & when
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        String before = harness.repository().findById(id).orElseThrow().canonicalCheckout();

        harness.service().cancel("merchant-a", id);

        CheckoutSession after = harness.repository().findById(id).orElseThrow();
        // then
        assertEquals(before, after.canonicalCheckout());
        assertEquals(CheckoutStatus.CANCELED, after.status());
    }

    @Test
    @DisplayName("재취소는 오류가 아니라 같은 응답을 재생한다")
    void cancel_alreadyCanceled_replaysSameResult() {
        // given & when
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");

        CancelResult first = harness.service().cancel("merchant-a", id);
        CancelResult second = harness.service().cancel("merchant-a", id);

        // then
        assertEquals(first, second);
        assertEquals(1L, harness.repository().findById(id).orElseThrow().version());
    }

    @Test
    @DisplayName("incomplete 도 취소할 수 있다")
    void cancel_incompleteCheckout_succeeds() {
        // given & when
        Harness harness = harness();
        Checkout created = harness.service().create("merchant-a",
                CheckoutTestSupport.request(null, CheckoutTestSupport.line("product-101", "product-101-270", 1)),
                "checkout-create-1");
        // then
        assertEquals("incomplete", created.status());

        assertEquals("canceled", harness.service().cancel("merchant-a", created.id()).status());
    }

    @Test
    @DisplayName("완료된 체크아웃 취소는 409 다")
    void cancel_completedCheckout_throwsNotModifiable() {
        // given & when
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        CheckoutSession current = harness.repository().findById(id).orElseThrow();
        harness.repository().put(current.withStatus(CheckoutStatus.COMPLETED));

        // then
        ApiException e = assertThrows(ApiException.class, () -> harness.service().cancel("merchant-a", id));

        assertEquals("CHECKOUT_NOT_MODIFIABLE", e.errorCode());
        assertEquals(409, e.status().value());
        assertEquals("completed", e.currentState());
    }

    @Test
    @DisplayName("진행 중인 체크아웃 취소도 409 다")
    void cancel_completeInProgressCheckout_throwsNotModifiable() {
        // given & when
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        CheckoutSession current = harness.repository().findById(id).orElseThrow();
        harness.repository().put(current.withStatus(CheckoutStatus.COMPLETE_IN_PROGRESS));

        // then
        ApiException e = assertThrows(ApiException.class, () -> harness.service().cancel("merchant-a", id));

        assertEquals("CHECKOUT_NOT_MODIFIABLE", e.errorCode());
        assertEquals("complete_in_progress", e.currentState());
    }

    @Test
    @DisplayName("취소된 체크아웃은 수정도 거절한다")
    void update_canceledCheckout_throwsNotModifiable() {
        // given & when
        Harness harness = harness();
        String id = cancelableId(harness, "checkout-create-1");
        harness.service().cancel("merchant-a", id);

        // then
        ApiException e = assertThrows(ApiException.class,
                () -> harness.service().update("merchant-a", id, runningShoesToSeoul()));

        assertEquals("CHECKOUT_NOT_MODIFIABLE", e.errorCode());
        assertEquals("canceled", e.currentState());
    }

    @Test
    @DisplayName("없는 체크아웃 취소는 404 다")
    void cancel_unknownId_throwsCheckoutNotFound() {
        // when & then
        ApiException e = assertThrows(ApiException.class, () -> harness().service().cancel("merchant-a", "nope"));

        assertEquals("CHECKOUT_NOT_FOUND", e.errorCode());
        assertEquals(404, e.status().value());
    }
}
