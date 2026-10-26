package com.copenhagen.pay.payment.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pay.notification")
public record PayNotificationProperties(Fcm fcm, Worker worker) {

    public PayNotificationProperties {
        fcm = fcm == null ? Fcm.disabled() : fcm;
        worker = worker == null ? Worker.disabled() : worker;
    }

    public record Fcm(
            boolean enabled,
            String projectId,
            String serviceAccountJson) {

        public Fcm {
            projectId = normalize(projectId);
            serviceAccountJson = normalize(serviceAccountJson);
        }

        private static Fcm disabled() {
            return new Fcm(false, "", "");
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record Worker(
            boolean enabled,
            long pollDelayMillis,
            int batchSize,
            long leaseDurationSeconds,
            long retryDelaySeconds) {

        public Worker {
            if (pollDelayMillis <= 0 || batchSize <= 0
                    || leaseDurationSeconds <= 0 || retryDelaySeconds <= 0) {
                throw new IllegalArgumentException("notification worker durations must be positive");
            }
        }

        private static Worker disabled() {
            return new Worker(false, 5000, 10, 60, 60);
        }
    }
}
