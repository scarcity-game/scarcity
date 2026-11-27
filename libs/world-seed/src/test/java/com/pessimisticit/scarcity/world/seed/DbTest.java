package com.pessimisticit.scarcity.world.seed;

import com.pessimisticit.scarcity.resourcemodel.JdbcResourceStore;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres (prod's version) with services/world's migrations. The test
 * area's ids are fixed, so every test starts from empty tables. Tests run in
 * parallel elsewhere (junit-platform.properties), but these share the tables:
 * each class runs on one thread and holds the "database" lock, so no two run
 * at once.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("database")
abstract class DbTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
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

    final ResourceStore resourceStore;

    DbTest() {
        this.resourceStore = new JdbcResourceStore(JDBC);
    }

    @BeforeEach
    void empty() {
        JDBC.execute("TRUNCATE resources, area_log, area_log_head");
    }

    /** The areas the seeder said it generated, in order. */
    final List<String> generated = new ArrayList<>();

    /** A seeder drawing from a fixed seed, noting each generated area in {@link #generated}. */
    Seeder seeder() {
        return new Seeder(resourceStore, new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE)),
                new SplittableRandom(42), (areaId, level) -> generated.add(areaId));
    }

    Resource get(String id) {
        return resourceStore.get(id).orElseThrow();
    }

    long version(String id) {
        return get(id).version();
    }
}
