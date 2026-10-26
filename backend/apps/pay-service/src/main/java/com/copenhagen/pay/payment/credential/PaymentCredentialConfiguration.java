package com.copenhagen.pay.payment.credential;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PaymentCredentialConfiguration {

    @Bean
    public Clock payClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder paymentPinPasswordEncoder(PayPinProperties properties) {
        PasswordEncoder argon2id = new Argon2PasswordEncoder(16, 32, 1, 64 * 1024, 3);
        return new PepperedPasswordEncoder(argon2id, properties.pepper());
    }
}
