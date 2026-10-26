package com.copenhagen.pay.payment.policy;

import java.util.Map;
import java.util.Set;

public final class PayStateTransitionPolicy {

    private static final Map<AuthorizationSessionStatus, Set<AuthorizationSessionStatus>>
            AUTHORIZATION_TRANSITIONS = Map.of(
                    AuthorizationSessionStatus.PAYMENT_METHOD_REQUIRED,
                    Set.of(
                            AuthorizationSessionStatus.PENDING,
                            AuthorizationSessionStatus.REJECTED,
                            AuthorizationSessionStatus.EXPIRED,
                            AuthorizationSessionStatus.INVALIDATED),
                    AuthorizationSessionStatus.PENDING,
                    Set.of(
                            AuthorizationSessionStatus.APPROVED,
                            AuthorizationSessionStatus.REJECTED,
                            AuthorizationSessionStatus.EXPIRED,
                            AuthorizationSessionStatus.INVALIDATED),
                    AuthorizationSessionStatus.APPROVED,
                    Set.of(
                            AuthorizationSessionStatus.EXPIRED,
                            AuthorizationSessionStatus.INVALIDATED));

    private static final Map<PaymentStatus, Set<PaymentStatus>> PAYMENT_TRANSITIONS = Map.of(
            PaymentStatus.READY,
            Set.of(PaymentStatus.PROCESSING),
            PaymentStatus.PROCESSING,
            Set.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED, PaymentStatus.UNKNOWN),
            PaymentStatus.UNKNOWN,
            Set.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED));

    private static final Map<ReconciliationJobStatus, Set<ReconciliationJobStatus>>
            RECONCILIATION_TRANSITIONS = Map.of(
                    ReconciliationJobStatus.PENDING,
                    Set.of(ReconciliationJobStatus.RUNNING, ReconciliationJobStatus.CANCELLED),
                    ReconciliationJobStatus.RUNNING,
                    Set.of(
                            ReconciliationJobStatus.PENDING,
                            ReconciliationJobStatus.SUCCEEDED,
                            ReconciliationJobStatus.FAILED,
                            ReconciliationJobStatus.EXHAUSTED,
                            ReconciliationJobStatus.CANCELLED),
                    ReconciliationJobStatus.FAILED,
                    Set.of(
                            ReconciliationJobStatus.PENDING,
                            ReconciliationJobStatus.RUNNING,
                            ReconciliationJobStatus.EXHAUSTED));

    /**
     * 판매처별 결제 결과. 세션은 APPROVED 에서 종료하므로 결제 성공/실패는 여기서 추적한다.
     * 판매처 A 성공 · 판매처 B 실패를 세션 하나의 상태로 표현할 수 없기 때문이다.
     */
    private static final Map<AuthorizationSessionItemStatus, Set<AuthorizationSessionItemStatus>>
            AUTHORIZATION_ITEM_TRANSITIONS = Map.of(
                    AuthorizationSessionItemStatus.PENDING,
                    Set.of(
                            AuthorizationSessionItemStatus.COMPLETED,
                            AuthorizationSessionItemStatus.INVALIDATED,
                            AuthorizationSessionItemStatus.EXPIRED));

    private PayStateTransitionPolicy() {
    }

    public static boolean canTransition(
            AuthorizationSessionStatus from,
            AuthorizationSessionStatus to) {
        return AUTHORIZATION_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean canTransition(
            AuthorizationSessionItemStatus from,
            AuthorizationSessionItemStatus to) {
        return AUTHORIZATION_ITEM_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return PAYMENT_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean canTransition(
            ReconciliationJobStatus from,
            ReconciliationJobStatus to) {
        return RECONCILIATION_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
