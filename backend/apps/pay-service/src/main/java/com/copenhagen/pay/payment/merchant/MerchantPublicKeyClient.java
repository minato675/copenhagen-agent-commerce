package com.copenhagen.pay.payment.merchant;

import java.util.List;
import java.util.Map;

public interface MerchantPublicKeyClient {

    List<Map<String, Object>> findPublicKeys(String merchantId);
}
