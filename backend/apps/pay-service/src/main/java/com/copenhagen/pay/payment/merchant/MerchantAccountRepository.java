package com.copenhagen.pay.payment.merchant;

import java.util.Optional;

public interface MerchantAccountRepository {

    Optional<MerchantAccount> findActiveByMerchantId(String merchantId);
}
