package com.copenhagen.merchantmock.support;

import java.util.UUID;

import javax.sql.DataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 일회용 PostgreSQL 하나를 띄우고 테스트마다 새 스키마를 준다(Jira 294).
 *
 * <p>pay-service 의 `ReconciliationPostgresIntegrationTest` 와 같은 구성이다. 인스턴스 기동이 비싸서
 * 클래스당 한 번만 띄우고, 테스트 사이 격리는 스키마를 새로 만드는 것으로 얻는다.
 *
 * <p>이 모듈의 다른 테스트 98개는 DB 없이 돈다. 여기 오는 것은 SQL 자체를 검증해야 하는 것뿐이다.
 * 기본 {@code test} 태스크는 {@code @Tag("postgres")} 를 빼고 돌린다(build.gradle).
 */
public final class EmbeddedPostgresSupport implements AutoCloseable {

    /** 마이그레이션 위치. 운영과 같은 파일을 그대로 쓴다. */
    public static final String MIGRATIONS = "classpath:db/migration";

    private final EmbeddedPostgres postgres;

    private EmbeddedPostgresSupport(EmbeddedPostgres postgres) {
        this.postgres = postgres;
    }

    public static EmbeddedPostgresSupport start() throws Exception {
        return new EmbeddedPostgresSupport(
                EmbeddedPostgres.builder().setServerConfig("listen_addresses", "127.0.0.1").start());
    }

    /** 아무도 쓰지 않는 새 스키마. 테스트 하나가 이걸 통째로 쓰고 버린다. */
    public String newSchema() {
        return "merchant_" + UUID.randomUUID().toString().replace("-", "");
    }

    public DataSource dataSourceFor(String schema) {
        return new DriverManagerDataSource(jdbcUrl(schema), "postgres", "postgres");
    }

    /** {@code @SpringBootTest} 가 {@code spring.datasource.url} 로 넘길 주소. 앱 전체를 이 인스턴스 위에 띄울 때 쓴다. */
    public String jdbcUrl(String schema) {
        return "jdbc:postgresql://127.0.0.1:" + postgres.getPort() + "/postgres?currentSchema=" + schema;
    }

    public JdbcTemplate jdbcFor(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Flyway 를 만든다. 스키마가 없으면 Flyway 가 만든다.
     *
     * @param target 여기까지만 적용한다. {@code null} 이면 최신까지 간다.
     */
    public Flyway flywayFor(DataSource dataSource, String schema, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(MIGRATIONS)
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    @Override
    public void close() throws Exception {
        postgres.close();
    }
}
