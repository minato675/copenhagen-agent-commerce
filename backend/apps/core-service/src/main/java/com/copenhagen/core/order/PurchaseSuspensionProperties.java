package com.copenhagen.core.order;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 승인 생성 일시 중단 스위치(이슈 S15P21A207-486). Pay가 승인 세션 스키마를 바꾸려면 진행 중인 세션이
 * 0이 되어야 한다. 그 드레인 구간에 Core가 새 승인 세션을 만들지 않게 막는다.
 *
 * <p>워커 4개는 {@code core.payment.worker.enabled=false}로 함께 내려가지만,
 * {@code POST /api/core/v1/orders/validations}는 워커를 거치지 않고 Pay를 부른다. 이 스위치가 그
 * 경로를 막는다.
 *
 * <p>드레인 중에도 {@code resume}은 접수되므로 구매 요청이 {@code CREATING_CHECKOUTS}에 쌓인다.
 * 인계 TTL이 30분이라 드레인 창은 그보다 짧아야 한다.
 */
@Component
@ConfigurationProperties("core.purchase")
public class PurchaseSuspensionProperties {

    private boolean approvalSuspended;

    public boolean isApprovalSuspended() {
        return approvalSuspended;
    }

    public void setApprovalSuspended(boolean approvalSuspended) {
        this.approvalSuspended = approvalSuspended;
    }
}
