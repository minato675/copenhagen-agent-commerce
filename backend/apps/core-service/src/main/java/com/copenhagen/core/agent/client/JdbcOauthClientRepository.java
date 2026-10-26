package com.copenhagen.core.agent.client;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOauthClientRepository implements OauthClientRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, client_id, client_secret_hash, client_name, client_type,
                redirect_uris::text AS redirect_uris, allowed_scopes::text AS allowed_scopes,
                logo_url, status, client_jwks::text AS client_jwks
            FROM oauth_clients
            """;

    private static final String FIND_BY_CLIENT_ID_SQL = SELECT_COLUMNS + " WHERE client_id = ?";
    private static final String FIND_BY_ID_SQL = SELECT_COLUMNS + " WHERE id = ?";

    private static final RowMapper<OauthClient> ROW_MAPPER = JdbcOauthClientRepository::mapClient;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOauthClientRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<OauthClient> findByClientId(String clientId) {
        return queryOne(FIND_BY_CLIENT_ID_SQL, clientId);
    }

    @Override
    public Optional<OauthClient> findById(long id) {
        return queryOne(FIND_BY_ID_SQL, id);
    }

    private Optional<OauthClient> queryOne(String sql, Object parameter) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, parameter));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private static OauthClient mapClient(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OauthClient(
                resultSet.getLong("id"),
                resultSet.getString("client_id"),
                resultSet.getString("client_secret_hash"),
                resultSet.getString("client_name"),
                resultSet.getString("client_type"),
                resultSet.getString("redirect_uris"),
                resultSet.getString("allowed_scopes"),
                resultSet.getString("logo_url"),
                resultSet.getString("status"),
                resultSet.getString("client_jwks"));
    }
}
