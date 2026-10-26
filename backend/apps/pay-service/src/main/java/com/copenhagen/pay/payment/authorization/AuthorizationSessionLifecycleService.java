package com.copenhagen.pay.payment.authorization;

import com.copenhagen.pay.common.error.PayErrorCode;
import com.copenhagen.pay.common.error.PayException;
import com.copenhagen.pay.payment.policy.AuthorizationSessionItemStatus;
import com.copenhagen.pay.payment.policy.AuthorizationSessionStatus;
import com.copenhagen.pay.payment.policy.PayStateTransitionPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 세션과 그 안의 판매처별 item 의 상태 전이.
 *
 * <p>결제 결과는 <b>item</b> 이 받는다. 세션은 사용자 인증까지만 책임지고 APPROVED 에서
 * 종료한다. 세션 하나에 결제가 N건 붙으므로, 먼저 끝난 결제가 세션을 종료 상태로 보내면
 * 나머지 판매처의 결과를 기록할 수 없기 때문이다.
 */
@Service
public class AuthorizationSessionLifecycleService {

    private final AuthorizationSessionRepository repository;
    private final AuthorizationSessionItemRepository itemRepository;

    public AuthorizationSessionLifecycleService(
            AuthorizationSessionRepository repository,
            AuthorizationSessionItemRepository itemRepository) {
        this.repository = repository;
        this.itemRepository = itemRepository;
    }

    @Transactional
    public void completeAfterPaymentSuccess(String sessionItemId) {
        transitionItem(
                sessionItemId,
                AuthorizationSessionItemStatus.PENDING,
                AuthorizationSessionItemStatus.COMPLETED);
    }

    @Transactional
    public void invalidateAfterDefinitivePaymentFailure(String sessionItemId) {
        transitionItem(
                sessionItemId,
                AuthorizationSessionItemStatus.PENDING,
                AuthorizationSessionItemStatus.INVALIDATED);
    }

    @Transactional
    public void expire(String authorizationSessionId) {
        AuthorizationSessionStatus currentStatus = findRequiredStatus(authorizationSessionId);
        if (currentStatus == AuthorizationSessionStatus.EXPIRED
                || currentStatus.isTerminal()) {
            return;
        }
        transition(authorizationSessionId, currentStatus, AuthorizationSessionStatus.EXPIRED);
    }

    private void transitionItem(
            String sessionItemId,
            AuthorizationSessionItemStatus expectedStatus,
            AuthorizationSessionItemStatus targetStatus) {
        AuthorizationSessionItemStatus currentStatus = findRequiredItemStatus(sessionItemId);
        if (currentStatus == targetStatus) {
            return;
        }
        if (!PayStateTransitionPolicy.canTransition(expectedStatus, targetStatus)) {
            throw itemStateConflict(currentStatus);
        }
        if (!itemRepository.transitionItemStatus(sessionItemId, expectedStatus, targetStatus)) {
            throw itemStateConflict(findRequiredItemStatus(sessionItemId));
        }
    }

    private void transition(
            String authorizationSessionId,
            AuthorizationSessionStatus expectedStatus,
            AuthorizationSessionStatus targetStatus) {
        if (!PayStateTransitionPolicy.canTransition(expectedStatus, targetStatus)) {
            throw stateConflict(expectedStatus);
        }
        if (!repository.transitionStatus(authorizationSessionId, expectedStatus, targetStatus)) {
            throw stateConflict(findRequiredStatus(authorizationSessionId));
        }
    }

    private AuthorizationSessionStatus findRequiredStatus(String authorizationSessionId) {
        return repository.findStatus(authorizationSessionId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
    }

    private AuthorizationSessionItemStatus findRequiredItemStatus(String sessionItemId) {
        return itemRepository.findItemStatus(sessionItemId)
                .orElseThrow(() -> new PayException(PayErrorCode.RESOURCE_NOT_FOUND));
    }

    private PayException stateConflict(AuthorizationSessionStatus currentStatus) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }

    private PayException itemStateConflict(AuthorizationSessionItemStatus currentStatus) {
        return new PayException(
                PayErrorCode.STATE_CONFLICT,
                PayErrorCode.STATE_CONFLICT.defaultMessage(),
                currentStatus.name(),
                PayErrorCode.STATE_CONFLICT.defaultNextAction());
    }
}
