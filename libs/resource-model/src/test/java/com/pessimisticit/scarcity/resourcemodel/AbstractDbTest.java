package com.pessimisticit.scarcity.resourcemodel;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** One real Postgres (same version as prod) and one Spring context shared by all DB tests. */
@SpringBootTest(classes = TestApp.class, properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/resource-model/resources.sql",
        "scarcity.resource-changes.listen=true"})
@AutoConfigureMockMvc
abstract class AbstractDbTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.6"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ResourceStore store;
    @Autowired
    ChangePublisher publisher;
    @Autowired
    ChangeSubscriber subscriber;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;

    static ObjectNode state(String k, Object v) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.putPOJO(k, v);
        return n;
    }

    String create(ResourceType type, String parent, ObjectNode state) {
        Resource r = Resource.newResource(type, state);
        tx.executeWithoutResult(s -> store.create(r, parent));
        return r.id();
    }

    static String unique() {
        return UUID.randomUUID().toString();
    }

    /** A raw LISTEN connection to read notification payloads as Postgres delivers them. */
    static final class RawListener implements AutoCloseable {
        final Connection c;

        RawListener() throws SQLException {
            c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            try (Statement s = c.createStatement()) {
                s.execute("LISTEN " + PostgresChangePublisher.CHANNEL);
            }
        }

        /** Collects payloads until {@code done} is satisfied or 10 s pass. */
        List<String> collect(java.util.function.Predicate<List<String>> done) throws SQLException {
            List<String> out = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 10_000;
            PGConnection pg = c.unwrap(PGConnection.class);
            while (!done.test(out) && System.currentTimeMillis() < deadline) {
                PGNotification[] ns = pg.getNotifications(200);
                if (ns != null) {
                    for (PGNotification n : ns) {
                        out.add(n.getParameter());
                    }
                }
            }
            return out;
        }

        @Override
        public void close() throws SQLException {
            c.close();
        }
    }
}
