package com.copenhagen.pay.payment.merchant;

public class MerchantKeyLookupException extends RuntimeException {

    public MerchantKeyLookupException(String message) {
        super(message);
    }

    public MerchantKeyLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
