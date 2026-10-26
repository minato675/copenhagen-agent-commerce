package com.copenhagen.pay.payment.merchant;

public record MerchantAccount(
        String id,
        String merchantId,
        String displayName,
        String status) {
}
