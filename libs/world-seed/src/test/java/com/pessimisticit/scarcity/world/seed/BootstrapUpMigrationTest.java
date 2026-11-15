package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * services/world's {@code V4__bootstrap_up}, run again on tables shaped as before #148: an area
 * seeded with no {@code up} gets the turn queue its first advance would have written.
 */
class BootstrapUpMigrationTest extends DbTest {

    private static final Path V4 = Path.of("../../services/world/src/main/resources/db/migration/V4__bootstrap_up.sql");
    private static final String USER_A = "entity:00000000-0000-4000-8000-0000000000aa";
    private static final String USER_B = "entity:00000000-0000-4000-8000-0000000000bb";
    private static final String BUSY = "entity:00000000-0000-4000-8000-000000000001";

    @Test
    void putsTheOrbiterUpInATestAreaSeededBeforeTurns() throws IOException {
        seedWithoutUp();

        migrate();

        assertThat(version(WorldIds.AREA)).isEqualTo(4);
        JsonNode area = get(WorldIds.AREA).state();
        assertThat(area.get("up").get("entity").asText()).isEqualTo(WorldIds.ORBITER);
        assertThat(area.get("up").get("controller").get("principal").asText()).isEqualTo("orbital");
        assertThat(area.get("up").get("since").asDouble()).isZero();
        assertThat(area.get("pending")).isEmpty();
        AreaState parsed = WorldJson.fromJson(area, AreaState.class);
        assertThat(parsed.up().entity()).isEqualTo(WorldIds.ORBITER);
        assertThat(version(WorldIds.ORBITER)).isEqualTo(1);
    }

    @Test
    void queuesIdleControlledEntitiesByIdFromTheAreasTime() throws IOException {
        seedWithoutUp();
        JDBC.update("UPDATE resources SET state = state || '{\"time\": 2.5}' WHERE id = ?", WorldIds.AREA);
        entity(USER_B, "{\"kind\":\"user\",\"sub\":\"b\"}", false);
        entity(USER_A, "{\"kind\":\"user\",\"sub\":\"a\"}", false);
        // Sorts first by id, but has an action in progress: not idle, not queued.
        entity(BUSY, "{\"kind\":\"user\",\"sub\":\"c\"}", true);

        migrate();

        JsonNode area = get(WorldIds.AREA).state();
        assertThat(area.get("up").get("entity").asText()).isEqualTo(WorldIds.ORBITER);
        assertThat(area.get("up").get("since").asDouble()).isEqualTo(2.5);
        assertThat(area.get("pending")).extracting(t -> t.get("entity").asText())
                .containsExactly(USER_A, USER_B);
        assertThat(area.get("pending").get(0).get("since").asDouble()).isEqualTo(2.5);
        WorldJson.fromJson(area, AreaState.class);
    }

    @Test
    void leavesAnAreaThatHasUpAlone() throws IOException {
        seeder().seed();

        migrate();

        assertThat(version(WorldIds.AREA)).isEqualTo(3);
    }

    @Test
    void leavesAnAreaThatHasAdvancedAlone() throws IOException {
        seedWithoutUp();
        JDBC.update("INSERT INTO area_log_head (area_id, last_seq) VALUES (?, 1)", WorldIds.AREA);

        migrate();

        assertThat(version(WorldIds.AREA)).isEqualTo(3);
        assertThat(get(WorldIds.AREA).state().has("up")).isFalse();
    }

    @Test
    void leavesAnAreaWithNobodyControlledAlone() throws IOException {
        seedWithoutUp();
        JDBC.update("UPDATE resources SET state = state - 'controller' WHERE id = ?", WorldIds.ORBITER);

        migrate();

        assertThat(version(WorldIds.AREA)).isEqualTo(3);
        assertThat(get(WorldIds.AREA).state().has("up")).isFalse();
    }

    /** The seed as it was before #148: no {@code up}, no {@code pending}. */
    private void seedWithoutUp() {
        seeder().seed();
        JDBC.update("UPDATE resources SET state = state - 'up' - 'pending' WHERE id = ?", WorldIds.AREA);
    }

    private static void entity(String id, String controller, boolean busy) {
        String action = busy ? ", \"action\": {\"name\": \"wait\", \"start\": 0.0, \"end\": 9.0}" : "";
        JDBC.update("INSERT INTO resources (id, type, parent_id, area_id, version, state) "
                + "VALUES (?, 'entity', ?, ?, 1, ?::jsonb)", id, WorldIds.AREA, WorldIds.AREA,
                "{\"entityKind\": \"mobile\", \"name\": \"e\", \"position\": {\"x\": 1.0, \"y\": 1.0}, "
                        + "\"controller\": " + controller + action + "}");
    }

    private static void migrate() throws IOException {
        JDBC.execute(Files.readString(V4.toAbsolutePath().normalize(), StandardCharsets.UTF_8));
    }
}
