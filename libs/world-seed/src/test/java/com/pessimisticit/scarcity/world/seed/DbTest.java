package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.resourcemodel.JdbcResourceStore;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres (prod's version) with services/world's migrations. The test
 * area's ids are fixed, so every test starts from empty tables.
 */
abstract class DbTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.6"));
    static final HikariDataSource DATA_SOURCE;
    static final JdbcTemplate JDBC;

    static {
        POSTGRES.start();
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        Path migrations = Path.of("../../services/world/src/main/resources/db/migration").toAbsolutePath().normalize();
        Flyway.configure().dataSource(DATA_SOURCE).locations("filesystem:" + migrations).load().migrate();
        JDBC = new JdbcTemplate(DATA_SOURCE);
    }

    final ResourceStore store;

    DbTest() {
        this.store = new JdbcResourceStore(JDBC);
    }

    @BeforeEach
    void empty() {
        JDBC.execute("TRUNCATE resources, area_log, area_log_head");
    }

    Seeder seeder() {
        return new Seeder(store, new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE)));
    }

    Resource get(String id) {
        return store.get(id).orElseThrow();
    }

    long version(String id) {
        return get(id).version();
    }
}
