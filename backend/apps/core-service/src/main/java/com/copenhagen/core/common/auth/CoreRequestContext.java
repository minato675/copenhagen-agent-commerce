package com.copenhagen.core.common.auth;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

public final class CoreRequestContext {

    private static final String PRINCIPAL_ATTRIBUTE = CoreRequestContext.class.getName() + ".principal";

    private CoreRequestContext() {
    }

    public static void setPrincipal(HttpServletRequest request, UUID userPublicId) {
        request.setAttribute(PRINCIPAL_ATTRIBUTE, userPublicId);
    }

    public static Optional<UUID> findPrincipal(HttpServletRequest request) {
        return Optional.ofNullable(request.getAttribute(PRINCIPAL_ATTRIBUTE)).map(UUID.class::cast);
    }

    /**
     * 보호 라우트에서만 호출한다 — {@code CoreAuthenticationFilter}가 이미 미인증 요청을 걸러내므로 정상적으로는
     * 항상 값이 있어야 한다. 없다면 필터 설정 누락 등 방어적으로 다뤄야 할 상태다.
     */
    public static UUID requirePrincipal(HttpServletRequest request) {
        return findPrincipal(request)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_MISSING));
    }
}
