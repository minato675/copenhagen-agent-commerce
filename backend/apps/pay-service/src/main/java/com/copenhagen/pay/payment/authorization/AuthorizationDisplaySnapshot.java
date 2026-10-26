package com.copenhagen.pay.payment.authorization;

import java.util.List;

public record AuthorizationDisplaySnapshot(
        String merchantName,
        List<SummaryItem> summary) {

    public AuthorizationDisplaySnapshot {
        summary = summary == null ? List.of() : List.copyOf(summary);
    }

    public record SummaryItem(
            String name,
            String option,
            int quantity,
            long amountMinor) {
    }
}
