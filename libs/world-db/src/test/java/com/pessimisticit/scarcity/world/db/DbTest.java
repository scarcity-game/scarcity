package com.pessimisticit.scarcity.world.db;

import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.JdbcResourceStore;
import com.pessimisticit.scarcity.resourcemodel.PostgresChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.PostgresChangeSubscriber;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.Engine;
import com.pessimisticit.scarcity.world.engine.Intent;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.random.RandomGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres (prod's version) with services/world's migrations, shared by
 * every test; each test seeds its own area, so tests don't see each other.
 * Batches are received the way subscriptions receives them: LISTEN, after commit.
 */
abstract class DbTest {

    static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    static final Instant T0 = Instant.parse("2026-11-14T12:00:00Z");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.6"));
    static final HikariDataSource DATA_SOURCE;
    static final JdbcTemplate JDBC;
    static final Map<String, List<ChangeBatch>> BATCHES = new ConcurrentHashMap<>();

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

    private static boolean listening;

    /**
     * Starts the one LISTEN connection. Not in the static initialiser: the
     * listener thread runs this class's lambdas, which wait for it to finish.
     */
    @BeforeAll
    static synchronized void listen() throws InterruptedException {
        if (listening) {
            return;
        }
        PostgresChangeSubscriber subscriber = new PostgresChangeSubscriber(() -> DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        subscriber.addListener(b -> BATCHES.computeIfAbsent(b.area(), a -> new CopyOnWriteArrayList<>()).add(b));
        subscriber.start();
        if (!subscriber.awaitListening(Duration.ofSeconds(10))) {
            throw new IllegalStateException("the subscriber is not listening");
        }
        listening = true;
    }

    final TransactionTemplate tx;
    final ResourceStore store;
    final AreaLock lock;
    final AreaRepository repo;
    final AreaLogReader reader;

    DbTest() {
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
        this.store = new JdbcResourceStore(JDBC);
        this.lock = new AreaLock(JDBC, LogSettings.defaults());
        this.repo = new AreaRepository(store, new PostgresChangePublisher(JDBC), JDBC,
            LogSettings.defaults());
        this.reader = new AreaLogReader(JDBC);
    }

    /** What the service will do for one intent (#112), in one transaction. */
    AdvanceResult advance(String areaId, Intent intent, RandomGenerator rng, Instant now) {
        return tx.execute(s -> {
            AreaHead head = lock.begin(areaId, now);
            AreaSnapshot snapshot = repo.load(areaId);
            AdvanceResult r = Engine.advance(snapshot, intent, rng, head.nextSeq(), Catalogue.standard());
            repo.commit(head, snapshot, r, TRACEPARENT);
            return r;
        });
    }

    /** The area's batches once {@code n} have arrived (fails after 10 s). */
    static List<ChangeBatch> batches(String areaId, int n) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (received(areaId).size() < n && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return received(areaId);
    }

    static List<ChangeBatch> received(String areaId) {
        return List.copyOf(BATCHES.getOrDefault(areaId, List.of()));
    }
}
