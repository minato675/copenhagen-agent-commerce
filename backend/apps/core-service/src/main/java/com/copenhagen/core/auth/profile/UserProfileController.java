package com.copenhagen.core.auth.profile;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.common.auth.CoreRequestContext;

@RestController
@RequestMapping("/api/core/v1/users/me")
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping
    public UserProfileResponse me(HttpServletRequest request) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return UserProfileResponse.from(userProfileService.getProfile(principal));
    }

    /**
     * 앱은 {@code name}/{@code loginId}/{@code email}을 필수로 역직렬화한다(APK 계약 역분석, 이슈 412).
     * {@code userId}는 앱이 쓰지 않지만 기존 계약(순서 10, D-9)을 깨지 않으려고 그대로 둔다.
     */
    public record UserProfileResponse(UUID userId, String loginId, String name, String email) {

        static UserProfileResponse from(UserProfileResult result) {
            return new UserProfileResponse(result.userId(), result.loginId(), result.name(), result.email());
        }
    }
}
