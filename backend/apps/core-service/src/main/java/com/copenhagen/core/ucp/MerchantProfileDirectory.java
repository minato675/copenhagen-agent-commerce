package com.copenhagen.core.ucp;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.nimbusds.jose.jwk.ECKey;
import com.copenhagen.core.common.error.CoreErrorCode;
import com.copenhagen.core.common.error.CoreException;
import com.copenhagen.core.product.MerchantsProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Merchant Business Profile을 lazy discovery하고 협상된 endpoint만 돌려준다. */
@Component
public class MerchantProfileDirectory {

    private static final String SHOPPING = "dev.ucp.shopping";
    private static final String SELECT_SQL = """
            SELECT id, ucp_profile_url, base_url, active_capabilities::text AS active_capabilities,
                profile_expires_at
            FROM merchants WHERE id = ? AND status = 'ACTIVE'
            """;
    private static final String UPDATE_SQL = """
            UPDATE merchants
            SET base_url = ?, business_profile = ?::jsonb, active_capabilities = ?::jsonb,
                profile_fetched_at = ?, profile_expires_at = ?
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoreUcpProperties properties;
    private final Clock clock;

    public MerchantProfileDirectory(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, CoreUcpProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public URI endpoint(MerchantsProperties.MerchantConfig config, String capability) {
        MerchantRow row = jdbcTemplate.query(SELECT_SQL, ROW_MAPPER, config.refId()).stream().findFirst()
                .orElseThrow(() -> new CoreException(CoreErrorCode.MERCHANT_PROFILE_UNAVAILABLE));
        Instant now = clock.instant();
        NegotiatedProfile negotiated = row.expiresAt() != null && now.isBefore(row.expiresAt())
                ? cached(row) : discover(row, now);
        if (!negotiated.capabilities().contains(capability)) {
            throw new CoreException(CoreErrorCode.MERCHANT_CAPABILITY_UNAVAILABLE);
        }
        return negotiated.endpoint();
    }

    public List<ECKey> signingKeys(MerchantsProperties.MerchantConfig config) {
        endpoint(config, "dev.ucp.shopping.order");
        String raw = jdbcTemplate.queryForObject(
                "SELECT business_profile::text FROM merchants WHERE id = ? AND status = 'ACTIVE'",
                String.class, config.refId());
        List<ECKey> keys = new ArrayList<>();
        try {
            for (JsonNode key : objectMapper.readTree(raw).path("keys")) {
                if ("EC".equals(key.path("kty").asText()) && !key.has("d")) {
                    keys.add(ECKey.parse(key.toString()));
                }
            }
            return List.copyOf(keys);
        } catch (java.text.ParseException | RuntimeException exception) {
            throw new CoreException(CoreErrorCode.MERCHANT_PROFILE_INVALID);
        }
    }

    private NegotiatedProfile cached(MerchantRow row) {
        try {
            JsonNode capabilities = objectMapper.readTree(row.activeCapabilities());
            Set<String> names = new LinkedHashSet<>();
            for (JsonNode entry : capabilities) {
                names.add(entry.asText());
            }
            return new NegotiatedProfile(URI.create(row.baseUrl()), Set.copyOf(names));
        } catch (RuntimeException exception) {
            throw new CoreException(CoreErrorCode.MERCHANT_PROFILE_INVALID);
        }
    }

    private synchronized NegotiatedProfile discover(MerchantRow row, Instant now) {
        // 동시 첫 호출도 이 인스턴스에서는 한 번만 Discovery한다. DB TTL이 프로세스 간 재호출을 줄인다.
        try {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(java.time.Duration.ofSeconds(3));
            factory.setReadTimeout(java.time.Duration.ofSeconds(5));
            String raw = RestClient.builder().requestFactory(factory).build()
                    .get().uri(row.profileUrl()).header("Accept", "application/json")
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(raw);
            NegotiatedProfile profile = validate(root, row.profileUrl());
            String active = objectMapper.writeValueAsString(profile.capabilities());
            jdbcTemplate.update(UPDATE_SQL, profile.endpoint().toString(), raw, active, Timestamp.from(now),
                    Timestamp.from(now.plus(properties.merchantProfileCache())), row.id());
            return profile;
        } catch (CoreException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new CoreException(CoreErrorCode.MERCHANT_PROFILE_UNAVAILABLE);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new CoreException(CoreErrorCode.MERCHANT_PROFILE_INVALID);
        }
    }

    private NegotiatedProfile validate(JsonNode root, String profileUrl) {
        JsonNode ucp = root.path("ucp");
        if (!CoreUcpProperties.UCP_VERSION.equals(ucp.path("version").asText())) {
            throw new CoreException(CoreErrorCode.MERCHANT_UCP_VERSION_UNSUPPORTED);
        }
        JsonNode service = ucp.path("services").path(SHOPPING);
        URI endpoint = null;
        for (JsonNode entry : service) {
            if (CoreUcpProperties.UCP_VERSION.equals(entry.path("version").asText())
                    && "rest".equals(entry.path("transport").asText())) {
                endpoint = URI.create(entry.path("endpoint").asText());
                break;
            }
        }
        if (endpoint == null || !sameOrigin(URI.create(profileUrl), endpoint)) {
            throw new CoreException(CoreErrorCode.MERCHANT_PROFILE_INVALID);
        }
        for (JsonNode key : root.path("keys")) {
            if (key.has("d")) {
                throw new CoreException(CoreErrorCode.MERCHANT_PROFILE_INVALID);
            }
        }
        Set<String> active = new LinkedHashSet<>();
        ucp.path("capabilities").properties().forEach(entry -> {
            for (JsonNode version : entry.getValue()) {
                if (CoreUcpProperties.UCP_VERSION.equals(version.path("version").asText())
                        && supported(entry.getKey())) {
                    active.add(entry.getKey());
                }
            }
        });
        return new NegotiatedProfile(endpoint, Set.copyOf(active));
    }

    private static boolean supported(String capability) {
        return capability.equals("dev.ucp.shopping.catalog.search")
                || capability.equals("dev.ucp.shopping.catalog.lookup")
                || capability.equals("dev.ucp.shopping.checkout")
                || capability.equals("dev.ucp.shopping.order")
                || capability.equals("dev.ucp.shopping.fulfillment");
    }

    private static boolean sameOrigin(URI profile, URI endpoint) {
        return endpoint.isAbsolute() && ("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                && endpoint.getUserInfo() == null && endpoint.getFragment() == null
                && profile.getScheme().equals(endpoint.getScheme())
                && profile.getHost().equals(endpoint.getHost()) && profile.getPort() == endpoint.getPort();
    }

    private record MerchantRow(
            long id, String profileUrl, String baseUrl, String activeCapabilities, Instant expiresAt) {
    }

    private record NegotiatedProfile(URI endpoint, Set<String> capabilities) {
    }

    private static final RowMapper<MerchantRow> ROW_MAPPER = MerchantProfileDirectory::map;

    private static MerchantRow map(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp expiresAt = resultSet.getTimestamp("profile_expires_at");
        return new MerchantRow(resultSet.getLong("id"), resultSet.getString("ucp_profile_url"),
                resultSet.getString("base_url"), resultSet.getString("active_capabilities"),
                expiresAt == null ? null : expiresAt.toInstant());
    }
}
