package com.copenhagen.merchantmock.catalog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;

import com.copenhagen.merchantmock.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code catalog_pool_state} 한 행의 SQL 검증(V12). ON CONFLICT (id) 와 CHECK (id = 1) 은 진짜 PostgreSQL 에서만 잡힌다. */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcCatalogPoolStateRepositoryPostgresTest {

    private EmbeddedPostgresSupport postgres;
    private JdbcCatalogPoolStateRepository repository;
    private JdbcTemplate jdbc;

    @BeforeAll
    void startPostgres() throws Exception {
        this.postgres = EmbeddedPostgresSupport.start();
    }

    @AfterAll
    void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() {
        String schema = postgres.newSchema();
        DataSource dataSource = postgres.dataSourceFor(schema);
        postgres.flywayFor(dataSource, schema, null).migrate();
        this.jdbc = postgres.jdbcFor(dataSource);
        this.repository = new JdbcCatalogPoolStateRepository(jdbc);
    }

    @Test
    @DisplayName("처음에는 상태가 없고 저장하면 한 행이 생기며 다시 저장하면 그 행을 덮는다")
    void save_twice_keepsSingleRow() {
        // given
        assertTrue(repository.find().isEmpty());
        Instant first = Instant.parse("2026-09-21T10:00:00Z");

        // when
        repository.save(new CatalogPoolState("a".repeat(64), "merchant-a,merchant-b", 30000, 30000, first));
        repository.save(new CatalogPoolState("b".repeat(64), "merchant-a,merchant-b,merchant-c", 0, 91234,
                first.plus(1, ChronoUnit.HOURS)));

        // then
        CatalogPoolState state = repository.find().orElseThrow();
        assertEquals("b".repeat(64), state.poolSha256());
        assertEquals("merchant-a,merchant-b,merchant-c", state.malls());
        assertEquals(0, state.poolLimit());
        assertEquals(91234, state.productCount());
        assertEquals(first.plus(1, ChronoUnit.HOURS), state.loadedAt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM catalog_pool_state", Integer.class));
        assertTrue(state.matches("b".repeat(64), "merchant-a,merchant-b,merchant-c", 0));
        assertTrue(!state.matches("b".repeat(64), "merchant-a,merchant-b,merchant-c", 30000));
    }
}
