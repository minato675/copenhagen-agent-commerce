package com.copenhagen.core.auth.profile;

import java.util.UUID;

/** {@code email}은 복호화된 평문이다. 저장 형태({@code users.email_encrypted})는 이 타입 밖으로 나오지 않는다. */
public record UserProfileResult(UUID userId, String loginId, String name, String email) {
}
