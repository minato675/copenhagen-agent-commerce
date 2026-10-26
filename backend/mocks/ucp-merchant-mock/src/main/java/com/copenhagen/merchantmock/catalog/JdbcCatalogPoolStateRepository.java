package com.copenhagen.merchantmock.catalog;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code catalog_pool_state} 한 행(V12). id 는 항상 1 이다. JdbcTemplate 만 쓴다(DESIGN.md 4절). */
@Repository
public class JdbcCatalogPoolStateRepository implements CatalogPoolStateRepository {

    private static final String SELECT = """
            SELECT pool_sha256, malls, pool_limit, product_count, loaded_at
              FROM catalog_pool_state
             WHERE id = 1
            """;

    private static final String UPSERT = """
            INSERT INTO catalog_pool_state (id, pool_sha256, malls, pool_limit, product_count, loaded_at)
            VALUES (1, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
                    SET pool_sha256   = EXCLUDED.pool_sha256,
                        malls         = EXCLUDED.malls,
                        pool_limit    = EXCLUDED.pool_limit,
                        product_count = EXCLUDED.product_count,
                        loaded_at     = EXCLUDED.loaded_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCatalogPoolStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<CatalogPoolState> find() {
        List<CatalogPoolState> rows = jdbcTemplate.query(SELECT, (rs, rowNum) -> new CatalogPoolState(
                rs.getString("pool_sha256"),
                rs.getString("malls"),
                rs.getInt("pool_limit"),
                rs.getInt("product_count"),
                rs.getTimestamp("loaded_at").toInstant()));
        return rows.stream().findFirst();
    }

    @Override
    public void save(CatalogPoolState state) {
        jdbcTemplate.update(UPSERT,
                state.poolSha256(),
                state.malls(),
                state.poolLimit(),
                state.productCount(),
                Timestamp.from(state.loadedAt()));
    }
}
