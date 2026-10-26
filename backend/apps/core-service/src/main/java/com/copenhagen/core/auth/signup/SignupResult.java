package com.copenhagen.core.auth.signup;

import java.util.UUID;

public record SignupResult(
        UUID userId,
        String loginId,
        String email,
        String name) {
}
