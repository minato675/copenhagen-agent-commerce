package com.copenhagen.pay.payment.credential;

import org.springframework.security.crypto.password.PasswordEncoder;

public class PepperedPasswordEncoder implements PasswordEncoder {

    private static final char PEPPER_SEPARATOR = '\u0000';

    private final PasswordEncoder delegate;
    private final String pepper;

    public PepperedPasswordEncoder(
            PasswordEncoder delegate,
            String pepper) {
        this.delegate = delegate;
        this.pepper = pepper;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(withPepper(rawPassword));
    }

    @Override
    public boolean matches(
            CharSequence rawPassword,
            String encodedPassword) {
        return delegate.matches(withPepper(rawPassword), encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return delegate.upgradeEncoding(encodedPassword);
    }

    private String withPepper(CharSequence rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("Raw PIN must not be null");
        }
        return rawPassword + String.valueOf(PEPPER_SEPARATOR) + pepper;
    }
}
