package com.copenhagen.pay.payment.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.copenhagen.pay.common.observability.PayLogCapture;
import com.copenhagen.pay.common.observability.PayLogContext;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FirebaseNotificationSenderTest {

    private static final String FID = "synthetic-fcm-token-not-for-logs";
    private static final NotificationMessage MESSAGE = new NotificationMessage(
            "push-001",
            NotificationEventType.PAYMENT_RESULT,
            "approval-001",
            "payment-001",
            "SUCCESS",
            "SHOW_PAYMENT_RESULT");

    @AfterEach
    void clearContext() {
        PayLogContext.clear();
    }

    @Test
    void firebaseSuccessLogsPushIdAndMaskedInstallation() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        when(messaging.send(any(Message.class))).thenReturn("firebase-message-001");
        FirebaseNotificationSender sender = new FirebaseNotificationSender(
                provider(messaging),
                properties(true));

        try (PayLogCapture logs = new PayLogCapture()) {
            sender.send(FID, MESSAGE);

            assertEquals(1, logs.count("notification.fcm.send.success"));
            assertTrue(logs.allText().contains("pushId=push-001"));
            assertTrue(logs.allText().contains("firebaseMessageId=firebase-message-001"));
            assertTrue(logs.allText().contains("durationMs="));
            assertTrue(logs.allText().contains("fidHash=" + PayLogContext.fingerprint(FID)));
            assertFalse(logs.allText().contains(FID));
        }
    }

    @Test
    void firebaseInvalidInstallationLogsFirebaseCodeAndReturnsSafeFailure() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        FirebaseMessagingException firebaseException = mock(FirebaseMessagingException.class);
        when(firebaseException.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(messaging.send(any(Message.class))).thenThrow(firebaseException);
        FirebaseNotificationSender sender = new FirebaseNotificationSender(
                provider(messaging),
                properties(true));

        try (PayLogCapture logs = new PayLogCapture()) {
            NotificationDeliveryException exception = assertThrows(
                    NotificationDeliveryException.class,
                    () -> sender.send(FID, MESSAGE));

            assertEquals("FCM_FID_UNREGISTERED", exception.errorCode());
            assertTrue(exception.invalidInstallation());
            assertEquals(1, logs.count("notification.fcm.send.failed"));
            assertTrue(logs.allText().contains("firebaseErrorCode=UNREGISTERED"));
            assertTrue(logs.allText().contains("invalidInstallation=true"));
            assertFalse(logs.allText().contains(FID));
        }
    }

    @Test
    void disabledFirebaseIsClassifiedWithoutAttemptingExternalSend() {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        FirebaseNotificationSender sender = new FirebaseNotificationSender(
                provider(messaging),
                properties(false));

        try (PayLogCapture logs = new PayLogCapture()) {
            NotificationDeliveryException exception = assertThrows(
                    NotificationDeliveryException.class,
                    () -> sender.send(FID, MESSAGE));

            assertEquals("FCM_DISABLED", exception.errorCode());
            assertEquals(1, logs.count("notification.fcm.send.skipped"));
            assertTrue(logs.allText().contains("reason=FCM_DISABLED"));
            assertFalse(logs.allText().contains(FID));
        }
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<FirebaseMessaging> provider(FirebaseMessaging messaging) {
        ObjectProvider<FirebaseMessaging> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(messaging);
        return provider;
    }

    private PayNotificationProperties properties(boolean enabled) {
        return new PayNotificationProperties(
                new PayNotificationProperties.Fcm(enabled, "synthetic-project", ""),
                new PayNotificationProperties.Worker(false, 5000, 10, 60, 60));
    }
}
