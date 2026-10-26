package com.copenhagen.pay.payment.credential.biometric;

import com.copenhagen.pay.common.auth.PayPrincipal;
import com.copenhagen.pay.common.web.PayRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pay/v1/users/me/biometric-credentials")
public class BiometricCredentialController {

    private static final String DEVICE_ID_HEADER = "X-Device-Id";

    private final BiometricCredentialService service;

    public BiometricCredentialController(BiometricCredentialService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BiometricCredentialResponse> register(
            @Valid @RequestBody RegisterBiometricCredentialRequest request,
            HttpServletRequest httpRequest) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(httpRequest);
        BiometricCredential credential = service.register(
                principal.subject(),
                request.deviceId(),
                request.publicKey(),
                request.credentialType());
        BiometricCredentialResponse response = new BiometricCredentialResponse(
                credential.id(),
                credential.registeredAt());
        return ResponseEntity.status(201).body(response);
    }

    /** 앱은 로컬 Keystore만으로 서버 등록 여부를 알 수 없다. 재설치·데이터 삭제 후 기준값이 된다. */
    @GetMapping
    public ResponseEntity<BiometricCredentialStatusResponse> status(
            @RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceId,
            HttpServletRequest httpRequest) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(httpRequest);
        return ResponseEntity.ok(service.findActive(principal.subject(), deviceId)
                .map(BiometricCredentialStatusResponse::registered)
                .orElseGet(BiometricCredentialStatusResponse::notRegistered));
    }

    /** 해제 대상이 없어도 204. 호출 후 상태는 언제나 "이 기기에 ACTIVE credential 없음"이다. */
    @DeleteMapping
    public ResponseEntity<Void> revoke(
            @RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceId,
            HttpServletRequest httpRequest) {
        PayPrincipal principal = PayRequestContext.requirePrincipal(httpRequest);
        service.revoke(principal.subject(), deviceId);
        return ResponseEntity.noContent().build();
    }

    public record BiometricCredentialStatusResponse(
            boolean registered,
            String status,
            String credentialId,
            Instant registeredAt) {

        private static BiometricCredentialStatusResponse registered(BiometricCredential credential) {
            return new BiometricCredentialStatusResponse(
                    true,
                    credential.status().name(),
                    credential.id(),
                    credential.registeredAt());
        }

        private static BiometricCredentialStatusResponse notRegistered() {
            return new BiometricCredentialStatusResponse(false, null, null, null);
        }
    }

    public record RegisterBiometricCredentialRequest(
            @NotBlank @Size(max = 150) String deviceId,
            @NotBlank @Size(max = 8192) String publicKey,
            @NotBlank @Size(max = 30) String credentialType) {

        @Override
        public String toString() {
            return "RegisterBiometricCredentialRequest["
                    + "deviceId='" + deviceId + '\''
                    + ", publicKey=<redacted>"
                    + ", credentialType='" + credentialType + '\''
                    + ']';
        }
    }

    public record BiometricCredentialResponse(
            String credentialId,
            Instant registeredAt) {
    }
}
