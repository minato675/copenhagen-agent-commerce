package com.copenhagen.core.ucp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.product.MerchantsProperties;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link MerchantProfileDirectory}의 caching·negotiation·validation 경로. */
class MerchantProfileDirectoryTest {

    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CHECKOUT = "dev.ucp.shopping.checkout";
    private static final String ENDPOINT_PATH = "/ucp/v1";

    private HttpServer server;
    private String responseBody;
    private int responseStatus;
    private AtomicInteger callCount;

    private JdbcTemplate jdbcTemplate;
    private MerchantProfileDirectory directory;
    private String profileUrl;
    private String origin;

    @BeforeEach
    void setUp() throws IOException {
        callCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/ucp", exchange -> {
            callCount.incrementAndGet();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        profileUrl = origin + "/.well-known/ucp";
        responseStatus = 200;
        responseBody = validProfile(origin + ENDPOINT_PATH);

        jdbcTemplate = mock(JdbcTemplate.class);
        directory = new MerchantProfileDirectory(
                jdbcTemplate, new ObjectMapper(),
                new CoreUcpProperties("https://core.example", Duration.ofMinutes(5)), CLOCK);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("캐시가 만료 전이면 재조회 없이 캐시된 endpoint를 돌려준다")
    void endpoint_cachedNotExpired_returnsCachedEndpointWithoutDiscovery() {
        stubRow(NOW.plusSeconds(60), origin + "/cached", """
                ["dev.ucp.shopping.checkout"]""");

        URI endpoint = directory.endpoint(config(), CHECKOUT);

        assertThat(endpoint).isEqualTo(URI.create(origin + "/cached"));
        assertThat(callCount.get()).isZero();
    }

    @Test
    @DisplayName("캐시에 요청한 capability가 없으면 MERCHANT_CAPABILITY_UNAVAILABLE")
    void endpoint_cachedCapabilityMissing_throwsCapabilityUnavailable() {
        stubRow(NOW.plusSeconds(60), origin + "/cached", """
                ["dev.ucp.shopping.catalog.search"]""");

        assertThatThrownBy(() -> directory.endpoint(config(), CHECKOUT))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.MERCHANT_CAPABILITY_UNAVAILABLE);
    }

    @Test
    @DisplayName("merchant 행이 없으면(비활성 포함) MERCHANT_PROFILE_UNAVAILABLE")
    void endpoint_merchantRowMissing_throwsProfileUnavailable() {
        stubEmptyRow();

        assertThatThrownBy(() -> directory.endpoint(config(), CHECKOUT))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.MERCHANT_PROFILE_UNAVAILABLE);
    }

    @Test
    @DisplayName("캐시가 만료되면 Discovery를 수행하고 endpoint·capability를 DB에 갱신한다")
    void endpoint_expiredCache_discoversAndUpdatesDb() {
        stubRow(NOW.minusSeconds(1), origin + "/stale", "[]");

        URI endpoint = directory.endpoint(config(), CHECKOUT);

        assertThat(endpoint).isEqualTo(URI.create(origin + ENDPOINT_PATH));
        assertThat(callCount.get()).isEqualTo(1);

        ArgumentCaptor<String> activeCapabilities = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                anyString(),
                eq(origin + ENDPOINT_PATH),
                anyString(),
                activeCapabilities.capture(),
                eq(Timestamp.from(NOW)),
                eq(Timestamp.from(NOW.plus(Duration.ofMinutes(5)))),
                eq(1L));
        assertThat(activeCapabilities.getValue())
                .contains("dev.ucp.shopping.checkout")
                .contains("dev.ucp.shopping.catalog.search")
                .contains("dev.ucp.shopping.catalog.lookup")
                .contains("dev.ucp.shopping.order")
                .contains("dev.ucp.shopping.fulfillment");
    }

    @Test
    @DisplayName("null expiresAt(최초 조회)이면 Discovery를 수행한다")
    void endpoint_nullExpiresAt_discovers() {
        stubRow(null, origin + "/stale", "[]");

        URI endpoint = directory.endpoint(config(), CHECKOUT);

        assertThat(endpoint).isEqualTo(URI.create(origin + ENDPOINT_PATH));
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("지원하지 않는 capability 이름은 negotiated set에서 제외된다")
    void discover_filtersUnknownCapabilities() {
        responseBody = """
                {"ucp":{"version":"2026-08-25",
                  "services":{"dev.ucp.shopping":[
                    {"version":"2026-08-25","transport":"rest","endpoint":"%s"}]},
                  "capabilities":{
                    "dev.ucp.shopping.checkout":[{"version":"2026-08-25"}],
                    "dev.ucp.shopping.unknown.thing":[{"version":"2026-08-25"}]}},
                 "keys":[]}
                """.formatted(origin + ENDPOINT_PATH);
        stubRow(null, origin + "/stale", "[]");

        directory.endpoint(config(), CHECKOUT);

        ArgumentCaptor<String> activeCapabilities = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(anyString(), anyString(), anyString(), activeCapabilities.capture(),
                any(), any(), eq(1L));
        assertThat(activeCapabilities.getValue())
                .contains("dev.ucp.shopping.checkout")
                .doesNotContain("dev.ucp.shopping.unknown.thing");
    }

    @Test
    @DisplayName("capability의 version이 Core 버전과 다르면 negotiated set에서 제외된다")
    void discover_excludesCapabilityWithMismatchedVersion() {
        responseBody = """
                {"ucp":{"version":"2026-08-25",
                  "services":{"dev.ucp.shopping":[
                    {"version":"2026-08-25","transport":"rest","endpoint":"%s"}]},
                  "capabilities":{
                    "dev.ucp.shopping.checkout":[{"version":"2025-01-01"}]}},
                 "keys":[]}
                """.formatted(origin + ENDPOINT_PATH);
        stubRow(null, origin + "/stale", "[]");

        assertThatThrownBy(() -> directory.endpoint(config(), CHECKOUT))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.MERCHANT_CAPABILITY_UNAVAILABLE);
    }

    @Test
    @DisplayName("버전이 다르면 MERCHANT_UCP_VERSION_UNSUPPORTED")
    void discover_versionMismatch_throwsUcpVersionUnsupported() {
        responseBody = """
                {"ucp":{"version":"2025-01-01","services":{},"capabilities":{}},"keys":[]}
                """;
        stubRow(null, origin + "/stale", "[]");

        assertThatThrownBy(() -> directory.endpoint(config(), CHECKOUT))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.MERCHANT_UCP_VERSION_UNSUPPORTED);
    }

    @Test
    @DisplayName("Merchant profile 응답의 endpoint가 profile URL과 다른 origin이면 MERCHANT_PROFILE_INVALID")
    void discover_crossOriginEndpoint_throwsProfileInvalid() {
        responseBody = validProfile("http://other-host.example:9999" + ENDPOINT_PATH);
        stubRow(null, origin + "/stale", "[]");

        assertThatThrownBy(() -> directory.endpoint(config(), CHECKOUT))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.MERCHANT_PROFILE_INVALID);
    }

    @Test
    @DisplayName("keys에 개인키(d)가 포함되면 MERCHANT_PROFILE_INVALID")
    void discover_privateKeyLeaked_throwsProfileInvalid() {
        responseBody = """
                {"ucp":{"version":"2026-08-25",
                  "services":{"dev.ucp.shopping":[
                    {"version":"2026-08-25","transport":"rest","endpoint":"%s"}]},
                  "capabilities":{"dev.ucp.shopping.checkout":[{"version":"2026-08-25"}]}},
                 "keys":[{"kty":"EC","kid":"k1","d":"secret"}]}
                """.formatted(origin + ENDPOINT_PATH);
        stubRow(null, origin + "/stale", "[]");

        assertThatThrownBy(() -> directory.endpoint(config(), CHECKOUT))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.MERCHANT_PROFILE_INVALID);
    }

    @Test
    @DisplayName("Merchant profile 응답이 깨진 JSON이면 MERCHANT_PROFILE_INVALID이고 DB를 갱신하지 않는다")
    void discover_malformedJson_throwsProfileInvalid() {
        responseBody = "not-json";
        stubRow(null, origin + "/stale", "[]");

        assertThatThrownBy(() -> directory.endpoint(config(), CHECKOUT))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.MERCHANT_PROFILE_INVALID);
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Merchant profile 조회가 HTTP 오류면 MERCHANT_PROFILE_UNAVAILABLE")
    void discover_httpError_throwsProfileUnavailable() {
        responseStatus = 503;
        stubRow(null, origin + "/stale", "[]");

        assertThatThrownBy(() -> directory.endpoint(config(), CHECKOUT))
                .isInstanceOf(CoreException.class)
                .extracting(exception -> ((CoreException) exception).errorCode())
                .isEqualTo(CoreErrorCode.MERCHANT_PROFILE_UNAVAILABLE);
    }

    private static String validProfile(String endpoint) {
        return """
                {"ucp":{"version":"2026-08-25",
                  "services":{"dev.ucp.shopping":[
                    {"version":"2026-08-25","transport":"rest","endpoint":"%s"}]},
                  "capabilities":{
                    "dev.ucp.shopping.catalog.search":[{"version":"2026-08-25"}],
                    "dev.ucp.shopping.catalog.lookup":[{"version":"2026-08-25"}],
                    "dev.ucp.shopping.checkout":[{"version":"2026-08-25"}],
                    "dev.ucp.shopping.order":[{"version":"2026-08-25"}],
                    "dev.ucp.shopping.fulfillment":[{"version":"2026-08-25"}]}},
                 "keys":[]}
                """.formatted(endpoint);
    }

    private static MerchantsProperties.MerchantConfig config() {
        return new MerchantsProperties.MerchantConfig(
                "merchant-a", 1L, "Copenhagen Demo Store", URI.create("http://ignored:8080"),
                Duration.ofSeconds(1), Duration.ofSeconds(3));
    }

    @SuppressWarnings("unchecked")
    private void stubRow(Instant expiresAt, String baseUrl, String activeCapabilitiesJson) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any())).thenAnswer(invocation -> {
            RowMapper<Object> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("id")).thenReturn(1L);
            when(resultSet.getString("ucp_profile_url")).thenReturn(profileUrl);
            when(resultSet.getString("base_url")).thenReturn(baseUrl);
            when(resultSet.getString("active_capabilities")).thenReturn(activeCapabilitiesJson);
            when(resultSet.getTimestamp("profile_expires_at"))
                    .thenReturn(expiresAt == null ? null : Timestamp.from(expiresAt));
            return List.of(mapper.mapRow(resultSet, 1));
        });
    }

    @SuppressWarnings("unchecked")
    private void stubEmptyRow() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    }
}
