package com.copenhagen.core.auth.signup;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/v1/auth/signup")
public class SignupController {

    private final SignupService signupService;

    public SignupController(SignupService signupService) {
        this.signupService = signupService;
    }

    @PostMapping
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        SignupResult result = signupService.signup(
                request.loginId(), request.password(), request.email(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(SignupResponse.from(result));
    }

    public record SignupRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z0-9_-]{5,20}$")
            String loginId,

            @NotBlank
            String password,

            @NotBlank
            @Email
            @Size(max = 254)
            String email,

            @NotBlank
            String name) {

        @Override
        public String toString() {
            return "SignupRequest[loginId='" + loginId + "', password=<redacted>, email=<redacted>, name='"
                    + name + "']";
        }
    }

    public record SignupResponse(
            UUID userId,
            String loginId,
            String email,
            String name) {

        static SignupResponse from(SignupResult result) {
            return new SignupResponse(result.userId(), result.loginId(), result.email(), result.name());
        }
    }
}
