package com.copenhagen.core.agent.token;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOauthClientAssertionJtiRepository implements OauthClientAssertionJtiRepository {

    private static final String INSERT_IF_ABSENT_SQL = """
            INSERT INTO oauth_client_assertion_jtis (oauth_client_id, jti, expires_at)
            VALUES (?, ?, ?)
            ON CONFLICT (oauth_client_id, jti) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOauthClientAssertionJtiRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryConsume(long oauthClientId, String jti, Instant expiresAt) {
        int rows = jdbcTemplate.update(INSERT_IF_ABSENT_SQL, oauthClientId, jti, Timestamp.from(expiresAt));
        return rows == 1;
    }
}
