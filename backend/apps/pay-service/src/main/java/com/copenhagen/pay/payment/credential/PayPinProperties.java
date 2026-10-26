package com.copenhagen.pay.payment.credential;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pay.pin")
public record PayPinProperties(String pepper) {

    public PayPinProperties {
        pepper = pepper == null ? "" : pepper;
    }
}
