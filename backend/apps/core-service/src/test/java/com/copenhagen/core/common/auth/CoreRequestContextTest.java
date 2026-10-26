package com.copenhagen.core.common.auth;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreRequestContextTest {

    @Test
    @DisplayName("principal이 설정돼 있으면 그대로 반환한다")
    void requirePrincipal_present_returnsValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID publicId = UUID.randomUUID();
        CoreRequestContext.setPrincipal(request, publicId);

        assertEquals(publicId, CoreRequestContext.requirePrincipal(request));
    }

    @Test
    @DisplayName("principal이 없으면 AUTH_TOKEN_MISSING 예외를 던진다")
    void requirePrincipal_missing_throws() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        CoreException exception = assertThrows(
                CoreException.class, () -> CoreRequestContext.requirePrincipal(request));

        assertEquals(CoreErrorCode.AUTH_TOKEN_MISSING, exception.errorCode());
    }
}
