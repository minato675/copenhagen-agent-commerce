package com.copenhagen.core.address;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.copenhagen.core.auth.user.UserRepository;
import com.copenhagen.core.common.auth.CoreRequestContext;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import com.fasterxml.jackson.annotation.JsonAlias;

@RestController
@RequestMapping("/api/core/v1/addresses")
public class AddressController {

    private final AddressService addressService;
    private final UserRepository userRepository;

    public AddressController(AddressService addressService, UserRepository userRepository) {
        this.addressService = addressService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<RegisterResponse> register(
            HttpServletRequest request, @Valid @RequestBody AddressRequest body) {
        long userId = requireUserId(request);
        UUID addressId = addressService.register(
                userId, body.recipientName(), body.recipientPhone(), body.postalCode(),
                body.addressLine1(), body.addressLine2(), body.deliveryRequest());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(addressId));
    }

    @GetMapping
    public ItemsResponse<AddressResponse> list(HttpServletRequest request) {
        long userId = requireUserId(request);
        List<AddressResponse> items = addressService.list(userId).stream()
                .map(AddressResponse::from)
                .toList();
        return new ItemsResponse<>(items);
    }

    @GetMapping("/{addressId}")
    public AddressResponse get(HttpServletRequest request, @PathVariable UUID addressId) {
        long userId = requireUserId(request);
        return AddressResponse.from(addressService.get(userId, addressId));
    }

    @PutMapping("/{addressId}")
    public AddressResponse update(
            HttpServletRequest request,
            @PathVariable UUID addressId,
            @Valid @RequestBody AddressRequest body) {
        long userId = requireUserId(request);
        AddressResult result = addressService.update(
                userId, addressId, body.recipientName(), body.recipientPhone(), body.postalCode(),
                body.addressLine1(), body.addressLine2(), body.deliveryRequest());
        return AddressResponse.from(result);
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable UUID addressId) {
        long userId = requireUserId(request);
        addressService.delete(userId, addressId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{addressId}/default")
    public ResponseEntity<Void> setDefault(HttpServletRequest request, @PathVariable UUID addressId) {
        long userId = requireUserId(request);
        addressService.setDefault(userId, addressId);
        return ResponseEntity.noContent().build();
    }

    /** Address 도메인은 {@code users.id}(FK)로 저장되므로 principal(publicId)을 내부 id로 변환한다. */
    private long requireUserId(HttpServletRequest request) {
        UUID principal = CoreRequestContext.requirePrincipal(request);
        return userRepository.findByPublicId(principal)
                .orElseThrow(() -> new CoreException(CoreErrorCode.AUTH_TOKEN_INVALID))
                .id();
    }

    public record AddressRequest(
        @NotBlank
        @JsonAlias("recipient")
        String recipientName,

        @NotBlank
        @JsonAlias("phone")
        String recipientPhone,

        @NotBlank
        String postalCode,

        @NotBlank
        @JsonAlias("address")
        String addressLine1,

        @JsonAlias("detail")
        String addressLine2,

        @JsonAlias("memo")
        String deliveryRequest
) {
}

    public record RegisterResponse(UUID addressId) {
    }

    public record AddressResponse(
            UUID addressId,
            String recipient,
            String phone,
            String postalCode,
            String address,
            String detail,
            String memo,
            boolean isDefault) {

        static AddressResponse from(AddressResult result) {
            return new AddressResponse(
                    result.addressId(), result.recipientName(), result.recipientPhone(), result.postalCode(),
                    result.addressLine1(),
                    Objects.requireNonNullElse(result.addressLine2(), ""),
                    Objects.requireNonNullElse(result.deliveryRequest(), ""),
                    result.isDefault());
        }
    }

    public record ItemsResponse<T>(List<T> items) {
    }
}
