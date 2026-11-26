package com.pessimisticit.scarcity.resourcemodel;

import java.util.Collections;
import com.pessimisticit.scarcity.world.model.Cell;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Game;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ItemTemplate;
import com.pessimisticit.scarcity.world.model.Mob;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;


/** One real Postgres (same version as prod) and one Spring context shared by all DB tests. */
@SpringBootTest(classes = TestApp.class, properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/resource-model/resources.sql",
        "scarcity.resource-changes.listen=true"})
@AutoConfigureMockMvc
abstract class AbstractDbTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

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

    /**
     * A new resource of {@code kind} with a fresh id and one value that tells it from the others:
     * a game's name, an area's time (its name otherwise), an entity's hp (its name otherwise), an
     * item's mass (1 g otherwise), a cell's clearance (0.5 m otherwise).
     */
    static Resource resource(ResourceKind kind, Object v) {
        Resource r = switch (kind) {
            case GAME -> new Game(String.valueOf(v));
            case AREA -> new Area(String.valueOf(v), v instanceof Number n ? n.doubleValue() : 0.0);
            case ENTITY -> {
                Entity mob = physical(new Mob("test", String.valueOf(v)));
                yield v instanceof Number n ? mob.withDamageable(true).withHp(n.doubleValue()) : mob;
            }
            case ITEM -> new ItemTemplate("rubble").of(v instanceof Number n ? n.doubleValue() : 1.0);
            case CELL -> Cell.of(null, Cell.Kind.FLOOR,
                    List.of(new Position(0, 0), new Position(1, 0), new Position(1, 1), new Position(0, 1)),
                    Collections.nCopies(4, new Cell.Neighbour(null, null)),
                    v instanceof Number n ? n.doubleValue() : 0.5);
        };
        return r.withId(Ids.newId(kind));
    }

    /** An entity named {@code tag} with {@code hp} n, to list by filter. */
    static Resource tagged(String tag, int n) {
        return physical(new Mob("test", tag)).withDamageable(true).withHp((double) n)
                .withId(Ids.newId(ResourceKind.ENTITY));
    }

    /** At (1, 1), with the mass and radius a stored entity needs. */
    static Entity physical(Entity e) {
        return e.withPosition(new Position(1.0, 1.0)).withMass(20.0).withRadius(0.04);
    }

    /** What {@link #resource} put in: the name, the time of an area or the hp of an entity that has one. */
    static Object value(Resource r) {
        return switch (r) {
            case Game g -> g.name();
            case Area a -> a.time();
            case Entity e -> e.hp() != null ? e.hp() : e.name();
            default -> throw new IllegalArgumentException(r.getClass().getName());
        };
    }

    String create(Resource resource, String parent) {
        tx.executeWithoutResult(s -> store.create(resource, parent));
        return resource.id();
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
