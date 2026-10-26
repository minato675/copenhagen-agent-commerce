package com.copenhagen.pay.payment.notification;

import java.time.Instant;
import java.util.List;

public interface NotificationInstallationRepository {

    void registerActive(String userId, String fid, Instant now);

    int deactivate(String userId, String fid, Instant now);

    boolean existsActiveForAnotherUser(String userId, String fid);

    List<String> findActiveFidsByUserId(String userId);
}
