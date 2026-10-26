package com.copenhagen.pay.payment.notification;

public class NotificationDeliveryException extends RuntimeException {

    private final String errorCode;
    private final boolean invalidInstallation;

    public NotificationDeliveryException(
            String errorCode,
            boolean invalidInstallation,
            Throwable cause) {
        super("Notification delivery failed", cause);
        this.errorCode = errorCode;
        this.invalidInstallation = invalidInstallation;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean invalidInstallation() {
        return invalidInstallation;
    }
}
