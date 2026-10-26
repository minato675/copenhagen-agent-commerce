package com.copenhagen.pay.payment.notification;

import com.copenhagen.pay.common.observability.PayLogContext;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FirebaseNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(FirebaseNotificationSender.class);

    private final ObjectProvider<FirebaseMessaging> firebaseMessaging;
    private final PayNotificationProperties properties;

    public FirebaseNotificationSender(
            ObjectProvider<FirebaseMessaging> firebaseMessaging,
            PayNotificationProperties properties) {
        this.firebaseMessaging = firebaseMessaging;
        this.properties = properties;
    }

    @Override
    public void send(String fid, NotificationMessage message) {
        String pushId = message.pushId();
        String fidHash = PayLogContext.fingerprint(fid);
        PayLogContext.put(PayLogContext.PUSH_ID, pushId);
        PayLogContext.put(PayLogContext.FID_HASH, fidHash);
        long startedAt = System.nanoTime();
        if (!properties.fcm().enabled()) {
            log.warn(
                    "notification.fcm.send.skipped pushId={} fidHash={} reason=FCM_DISABLED",
                    pushId,
                    fidHash);
            throw new NotificationDeliveryException("FCM_DISABLED", false, null);
        }
        FirebaseMessaging messaging = firebaseMessaging.getIfAvailable();
        if (messaging == null) {
            log.error(
                    "notification.fcm.send.skipped pushId={} fidHash={} reason=FCM_NOT_INITIALIZED",
                    pushId,
                    fidHash);
            throw new NotificationDeliveryException("FCM_NOT_INITIALIZED", false, null);
        }
        Message.Builder builder = Message.builder()
                .setFid(fid)
                .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder()
                        .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH).build())
                .putAllData(data(message));
        try {
            String firebaseMessageId = messaging.send(builder.build());
            log.info(
                    "notification.fcm.send.success pushId={} fidHash={} firebaseMessageId={} durationMs={}",
                    pushId,
                    fidHash,
                    firebaseMessageId,
                    elapsedMillis(startedAt));
        } catch (FirebaseMessagingException exception) {
            boolean invalidInstallation = exception.getMessagingErrorCode()
                    == MessagingErrorCode.UNREGISTERED;
            String firebaseErrorCode = exception.getMessagingErrorCode() == null
                    ? "UNKNOWN"
                    : exception.getMessagingErrorCode().name();
            log.error(
                    "notification.fcm.send.failed pushId={} fidHash={} firebaseErrorCode={} "
                            + "invalidInstallation={} durationMs={}",
                    pushId,
                    fidHash,
                    firebaseErrorCode,
                    invalidInstallation,
                    elapsedMillis(startedAt));
            throw new NotificationDeliveryException(
                    invalidInstallation ? "FCM_FID_UNREGISTERED" : "FCM_SEND_FAILED",
                    invalidInstallation,
                    exception);
        }
    }

    private Map<String, String> data(NotificationMessage message) {
        return Map.of(
                "eventId", message.eventId(),
                "pushId", message.pushId(),
                "type", message.type().name(),
                "approvalId", valueOrEmpty(message.approvalId()),
                "purchaseRequestId", valueOrEmpty(message.purchaseRequestId()),
                "paymentId", valueOrEmpty(message.paymentId()),
                "status", valueOrEmpty(message.status()),
                "nextAction", message.nextAction());
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
