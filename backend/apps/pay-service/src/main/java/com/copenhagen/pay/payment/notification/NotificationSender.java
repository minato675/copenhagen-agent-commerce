package com.copenhagen.pay.payment.notification;

public interface NotificationSender {

    void send(String fid, NotificationMessage message);
}
