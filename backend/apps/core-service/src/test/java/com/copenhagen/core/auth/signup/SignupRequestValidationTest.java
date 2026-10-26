package com.copenhagen.core.auth.signup;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.copenhagen.core.auth.signup.SignupController.SignupRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignupRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    private static SignupRequest request(String loginId, String password, String email, String name) {
        return new SignupRequest(loginId, password, email, name);
    }

    private boolean hasViolationOn(SignupRequest request, String propertyPath) {
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);
        Set<String> paths = violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
        return paths.contains(propertyPath);
    }

    // ===== loginId =====

    @ParameterizedTest
    @ValueSource(strings = {"abcde", "user_name", "user-name", "abcdefghij0123456789"})
    @DisplayName("loginId 허용 문자·경계값(5자/20자)은 통과한다")
    void loginId_validValues_noViolation(String loginId) {
        SignupRequest request = request(loginId, "Password1!", "user@example.com", "홍길동");

        assertFalse(hasViolationOn(request, "loginId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcd",                     // 4자 (너무 짧음)
            "abcdefghijklmnopqrstu",     // 21자 (너무 김)
            "Abcde",                     // 대문자 포함
            "user name",                 // 공백 포함
            "user.name",                 // 마침표
            "user!name"                  // 특수문자
    })
    @DisplayName("loginId 형식 위반은 거부한다")
    void loginId_invalidValues_hasViolation(String loginId) {
        SignupRequest request = request(loginId, "Password1!", "user@example.com", "홍길동");

        assertTrue(hasViolationOn(request, "loginId"));
    }

    @Test
    @DisplayName("loginId가 비어 있으면 거부한다")
    void loginId_blank_hasViolation() {
        SignupRequest request = request("", "Password1!", "user@example.com", "홍길동");

        assertTrue(hasViolationOn(request, "loginId"));
    }

    // ===== email =====

    @Test
    @DisplayName("email 형식이 올바르면 통과한다")
    void email_validFormat_noViolation() {
        SignupRequest request = request("user1234", "Password1!", "user@example.com", "홍길동");

        assertFalse(hasViolationOn(request, "email"));
    }

    @Test
    @DisplayName("email 형식이 올바르지 않으면 거부한다")
    void email_invalidFormat_hasViolation() {
        SignupRequest request = request("user1234", "Password1!", "not-an-email", "홍길동");

        assertTrue(hasViolationOn(request, "email"));
    }

    @Test
    @DisplayName("email이 254자를 초과하면 거부한다")
    void email_exceedsMaxLength_hasViolation() {
        String localPart = "a".repeat(250);
        SignupRequest request = request("user1234", "Password1!", localPart + "@example.com", "홍길동");

        assertTrue(hasViolationOn(request, "email"));
    }

    @Test
    @DisplayName("email이 비어 있으면 거부한다")
    void email_blank_hasViolation() {
        SignupRequest request = request("user1234", "Password1!", "", "홍길동");

        assertTrue(hasViolationOn(request, "email"));
    }

    // ===== password / name: @NotBlank만 Bean Validation, 나머지는 서비스에서 검증 =====

    @Test
    @DisplayName("password가 비어 있으면 거부한다")
    void password_blank_hasViolation() {
        SignupRequest request = request("user1234", "", "user@example.com", "홍길동");

        assertTrue(hasViolationOn(request, "password"));
    }

    @Test
    @DisplayName("name이 비어 있으면 거부한다")
    void name_blank_hasViolation() {
        SignupRequest request = request("user1234", "Password1!", "user@example.com", "");

        assertTrue(hasViolationOn(request, "name"));
    }

    @Test
    @DisplayName("password는 Bean Validation 단계에서 길이·조합 규칙을 검사하지 않는다")
    void password_lengthAndComposition_notValidatedHere() {
        // 8~20자·2종 조합 등은 SignupService.validatePassword()의 책임이다.
        SignupRequest request = request("user1234", "a", "user@example.com", "홍길동");

        assertFalse(hasViolationOn(request, "password"));
    }

    @Test
    @DisplayName("toString은 password·email을 노출하지 않는다")
    void toString_redactsSensitiveFields() {
        SignupRequest request = request("user1234", "Password1!", "user@example.com", "홍길동");

        String text = request.toString();

        assertFalse(text.contains("Password1!"));
        assertFalse(text.contains("user@example.com"));
    }
}
