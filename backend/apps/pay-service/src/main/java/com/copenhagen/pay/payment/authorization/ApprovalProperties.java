package com.copenhagen.pay.payment.authorization;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pay.approval")
public record ApprovalProperties(
        long expirationSeconds,
        long challengeExpirationSeconds,
        Boolean expirationFloorEnabled) {

    public ApprovalProperties {
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("pay.approval.expiration-seconds must be positive");
        }
        if (challengeExpirationSeconds <= 0) {
            throw new IllegalArgumentException(
                    "pay.approval.challenge-expiration-seconds must be positive");
        }
        expirationFloorEnabled = expirationFloorEnabled == null || expirationFloorEnabled;
    }

    /**
     * 세션 만료를 판매처별 checkout 만료 중 가장 이른 시각으로 잡되 기본 TTL 을 하한으로 둘지 여부.
     *
     * <p>하한이 없으면 짧은 만료를 주는 판매처 하나가 세션 전체를 끌어내려, 인증 횟수를 1회로
     * 줄인 효과가 상쇄된다. 반대로 하한을 두면 세션 만료가 일부 checkout 만료보다 늦어질 수
     * 있고 그 판매처의 complete 이 실패한다. 이 실패는 부분완료 정책으로 흡수된다.
     */
    public boolean floorEnabled() {
        return Boolean.TRUE.equals(expirationFloorEnabled);
    }
}
