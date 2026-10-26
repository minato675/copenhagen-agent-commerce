package com.copenhagen.core.address;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.copenhagen.core.common.crypto.PiiEncryptor;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddressServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long USER_ID = 1L;
    private static final String BASE64_KEY = "ZGV2LW9ubHktY29yZS1waWkta2V5LTMyLWJ5dGVzISE=";

    private AddressRepository addressRepository;
    private AddressService service;

    @BeforeEach
    void setUp() {
        addressRepository = mock(AddressRepository.class);
        PiiEncryptor piiEncryptor = new PiiEncryptor(BASE64_KEY);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AddressService(addressRepository, piiEncryptor, clock);
    }

    @Test
    @DisplayName("첫 배송지 등록은 자동으로 기본 배송지가 된다")
    void register_firstAddress_isDefault() {
        when(addressRepository.countActiveByUserId(USER_ID)).thenReturn(0);
        when(addressRepository.insert(
                any(), eq(USER_ID), eq(true), any(), any(), any(), any(), any(), any(), eq(NOW)))
                .thenReturn(10L);

        UUID addressId = service.register(
                USER_ID, "홍길동", "010-1234-5678", "12345", "서울시", "101동", "문 앞");

        assertNotNull(addressId);
        verify(addressRepository).insert(
                eq(addressId), eq(USER_ID), eq(true), any(), any(), any(), any(), any(), any(), eq(NOW));
    }

    @Test
    @DisplayName("두 번째 배송지 등록은 기본 배송지가 아니다")
    void register_secondAddress_notDefault() {
        when(addressRepository.countActiveByUserId(USER_ID)).thenReturn(1);

        service.register(USER_ID, "홍길동", "010-1234-5678", "12345", "서울시", null, null);

        verify(addressRepository).insert(
                any(), eq(USER_ID), eq(false), any(), any(), any(), any(), isNull(), isNull(), eq(NOW));
    }

    @Test
    @DisplayName("20개를 초과해 등록하면 ADDRESS_LIMIT_EXCEEDED다")
    void register_overLimit_throws() {
        when(addressRepository.countActiveByUserId(USER_ID)).thenReturn(20);

        CoreException exception = assertThrows(CoreException.class,
                () -> service.register(USER_ID, "홍길동", "010-1234-5678", "12345", "서울시", null, null));

        assertEquals(CoreErrorCode.ADDRESS_LIMIT_EXCEEDED, exception.errorCode());
    }

    @Test
    @DisplayName("목록 조회는 저장된 값을 복호화해 반환한다")
    void list_returnsDecryptedResults() {
        PiiEncryptor piiEncryptor = new PiiEncryptor(BASE64_KEY);
        UUID publicId = UUID.randomUUID();
        Address address = new Address(
                10L, publicId, USER_ID,
                piiEncryptor.encrypt("홍길동"), piiEncryptor.encrypt("010-1234-5678"),
                piiEncryptor.encrypt("12345"), piiEncryptor.encrypt("서울시"),
                piiEncryptor.encrypt("101동"), piiEncryptor.encrypt("문 앞"),
                true, NOW, NOW);
        when(addressRepository.findAllActiveByUserId(USER_ID)).thenReturn(List.of(address));

        List<AddressResult> results = service.list(USER_ID);

        assertEquals(1, results.size());
        AddressResult result = results.get(0);
        assertEquals(publicId, result.addressId());
        assertEquals("홍길동", result.recipientName());
        assertEquals("010-1234-5678", result.recipientPhone());
        assertTrue(result.isDefault());
    }

    @Test
    @DisplayName("존재하지 않는 배송지를 수정하면 ADDRESS_NOT_FOUND다")
    void update_missingAddress_throws() {
        UUID addressId = UUID.randomUUID();
        when(addressRepository.findActiveByPublicIdAndUserId(addressId, USER_ID)).thenReturn(Optional.empty());

        CoreException exception = assertThrows(CoreException.class,
                () -> service.update(
                        USER_ID, addressId, "홍길동", "010-1234-5678", "12345", "서울시", null, null));

        assertEquals(CoreErrorCode.ADDRESS_NOT_FOUND, exception.errorCode());
    }

    @Test
    @DisplayName("다른 사용자의 배송지를 삭제하면 ADDRESS_NOT_FOUND다")
    void delete_otherUsersAddress_throws() {
        UUID addressId = UUID.randomUUID();
        when(addressRepository.findActiveByPublicIdAndUserId(addressId, USER_ID)).thenReturn(Optional.empty());

        CoreException exception = assertThrows(CoreException.class, () -> service.delete(USER_ID, addressId));

        assertEquals(CoreErrorCode.ADDRESS_NOT_FOUND, exception.errorCode());
    }

    @Test
    @DisplayName("기본 배송지를 삭제하면 남은 배송지 중 최신 등록순 1건이 새 기본으로 승격된다")
    void delete_defaultAddress_promotesNextDefault() {
        UUID deletedId = UUID.randomUUID();
        Address deleted = addressOf(deletedId, true);
        when(addressRepository.findActiveByPublicIdAndUserId(deletedId, USER_ID)).thenReturn(Optional.of(deleted));

        Address remaining = addressOf(UUID.randomUUID(), false);
        when(addressRepository.findAllActiveByUserId(USER_ID)).thenReturn(List.of(remaining));

        service.delete(USER_ID, deletedId);

        verify(addressRepository).softDelete(deleted.id(), NOW);
        verify(addressRepository).setDefault(remaining.id(), NOW);
    }

    @Test
    @DisplayName("기본 배송지를 삭제했는데 남은 배송지가 없으면 승격하지 않는다")
    void delete_defaultAddress_noRemaining_doesNotPromote() {
        UUID deletedId = UUID.randomUUID();
        Address deleted = addressOf(deletedId, true);
        when(addressRepository.findActiveByPublicIdAndUserId(deletedId, USER_ID)).thenReturn(Optional.of(deleted));
        when(addressRepository.findAllActiveByUserId(USER_ID)).thenReturn(List.of());

        service.delete(USER_ID, deletedId);

        verify(addressRepository, never()).setDefault(anyLong(), any());
    }

    @Test
    @DisplayName("기본이 아닌 배송지를 삭제하면 승격 로직이 실행되지 않는다")
    void delete_nonDefaultAddress_doesNotPromote() {
        UUID deletedId = UUID.randomUUID();
        Address deleted = addressOf(deletedId, false);
        when(addressRepository.findActiveByPublicIdAndUserId(deletedId, USER_ID)).thenReturn(Optional.of(deleted));

        service.delete(USER_ID, deletedId);

        verify(addressRepository).softDelete(deleted.id(), NOW);
        verify(addressRepository, never()).findAllActiveByUserId(USER_ID);
        verify(addressRepository, never()).setDefault(anyLong(), any());
    }

    @Test
    @DisplayName("이미 기본인 배송지를 다시 기본 지정하면 멱등하게 아무 것도 바꾸지 않는다")
    void setDefault_alreadyDefault_isNoop() {
        UUID addressId = UUID.randomUUID();
        Address address = addressOf(addressId, true);
        when(addressRepository.findActiveByPublicIdAndUserId(addressId, USER_ID)).thenReturn(Optional.of(address));

        service.setDefault(USER_ID, addressId);

        verify(addressRepository, never()).clearDefault(anyLong(), any());
        verify(addressRepository, never()).setDefault(anyLong(), any());
    }

    @Test
    @DisplayName("기본이 아닌 배송지를 기본 지정하면 기존 기본을 해제한 뒤 새로 지정한다")
    void setDefault_notDefault_clearsThenSets() {
        UUID addressId = UUID.randomUUID();
        Address address = addressOf(addressId, false);
        when(addressRepository.findActiveByPublicIdAndUserId(addressId, USER_ID)).thenReturn(Optional.of(address));

        service.setDefault(USER_ID, addressId);

        verify(addressRepository, times(1)).clearDefault(USER_ID, NOW);
        verify(addressRepository, times(1)).setDefault(address.id(), NOW);
    }

    private static final Random ID_GENERATOR = new Random();

    private static Address addressOf(UUID publicId, boolean isDefault) {
        PiiEncryptor piiEncryptor = new PiiEncryptor(BASE64_KEY);
        return new Address(
                ID_GENERATOR.nextLong(1, 1_000_000),
                publicId, USER_ID,
                piiEncryptor.encrypt("홍길동"), piiEncryptor.encrypt("010-1234-5678"),
                piiEncryptor.encrypt("12345"), piiEncryptor.encrypt("서울시"),
                null, null,
                isDefault, NOW, NOW);
    }
}
