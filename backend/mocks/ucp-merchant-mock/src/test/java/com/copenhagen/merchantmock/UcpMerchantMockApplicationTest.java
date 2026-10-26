package com.copenhagen.merchantmock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class UcpMerchantMockApplicationTest {

    @Test
    @DisplayName("애플리케이션 진입점 클래스가 존재한다")
    void applicationClass_always_exists() {
        // when & then
        assertNotNull(UcpMerchantMockApplication.class);
    }
}
