package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Placeholder;
import com.pessimisticit.scarcity.world.model.Stairs;
import com.pessimisticit.scarcity.world.seed.WorldIds;
import org.junit.jupiter.api.Test;

/** Taking stairs makes the level below, then carries the traveller there and back (#168). */
class CrossingTest extends WorldTest {

    private static final String STAIRS = "entity:00000000-0000-4000-8000-000000000005";

    private String token() {
        return TestSupport.userToken(SUB);
    }

    private void setUp() throws Exception {
        insertHero();
        Entity hero = entity(HERO).withActions(java.util.List.of("step", "wait", "traverse"));
        Entity stairs = Stairs.down("stairs").withPosition(new com.pessimisticit.scarcity.world.model.Position(2.5, 2.0));
        tx.executeWithoutResult(s -> {
            store.update(hero);
            store.create(stairs.withId(STAIRS), WorldIds.AREA);
        });
    }

    private JsonNode traverse(String entity, String stairs) throws Exception {
        String body = "{\"entity\":\"" + entity + "\",\"actionSpec\":{\"type\":\"traverse\",\"params\":{\"stairs\":\""
                + stairs + "\"}}}";
        var response = postIntent(body, token());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    /** The level's mouse, played by microbe. */
    private String mouseIn(String areaId) {
        return store.children(areaId, com.pessimisticit.scarcity.world.model.ResourceKind.ENTITY, 0, 50).items()
                .stream().filter(e -> ((Entity) e).prototype().equals("mouse")).findFirst().orElseThrow().id();
    }

    private void mouseWaits(String mouse) throws Exception {
        var response = postIntent(waitIntent(mouse, 1.0), TestSupport.serviceToken("microbe"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    /**
     * The hero starts the stairs; the orbiter's wait of one time unit lets them end. The warm-up of
     * the level below stops for its idle mouse (#190); the mouse's wait lets the hero arrive.
     */
    private void crossFirst() throws Exception {
        traverse(HERO, STAIRS);
        var response = postIntent(waitIntent(WorldIds.ORBITER, 1.0), ORBITAL);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String below = store.areaOf(((Stairs) entity(STAIRS)).to()).orElseThrow();
        assertThat(store.exists(HERO)).as("the hero is still on its way").isFalse();
        mouseWaits(mouseIn(below));
    }

    @Test
    void theFirstCrossingMakesTheLevelAndTheTravellerArrivesInIt() throws Exception {
        setUp();
        crossFirst();

        assertThat(store.exists(HERO)).isTrue();
        String below = store.areaOf(HERO).orElseThrow();
        assertThat(below).isNotEqualTo(WorldIds.AREA);
        Area level = (Area) resource(below);
        assertThat(level.depth()).isEqualTo(1);
        assertThat(level.time()).as("the area has caught up to the game").isEqualTo(area().time());
        Stairs down = (Stairs) entity(STAIRS);
        Entity pair = entity(down.to());
        assertThat(pair).isInstanceOf(Stairs.class);
        assertThat(((Stairs) pair).to()).isEqualTo(STAIRS);
        assertThat(store.areaOf(down.to())).contains(below);
        assertThat(entity(HERO).position()).isEqualTo(pair.position());
        assertThat(entity(HERO).controller()).isNotNull();
        var items = store.children(below, com.pessimisticit.scarcity.world.model.ResourceKind.ENTITY, 0, 50).items();
        assertThat(items).as("the placeholder is gone").noneMatch(Placeholder.class::isInstance);
        assertThat(items).as("stairs up and down").filteredOn(Stairs.class::isInstance).hasSize(2);
        assertThat(items).as("a mouse").anyMatch(e -> ((Entity) e).prototype().equals("mouse"));
    }

    @Test
    void goingBackCatchesTheFirstAreaUp() throws Exception {
        setUp();
        crossFirst();
        String pair = ((Stairs) entity(STAIRS)).to();
        traverse(HERO, pair);
        // The mouse is idle with the hero, so it is up next; its wait lets the traverse end.
        String below = store.areaOf(pair).orElseThrow();
        String mouse = mouseIn(below);
        mouseWaits(mouse);
        // Back up, the area waits for the orbiter, idle since the hero left; its wait brings the hero in.
        assertThat(store.exists(HERO)).as("the hero is still on its way").isFalse();
        var response = postIntent(waitIntent(WorldIds.ORBITER, 1.0), ORBITAL);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        assertThat(store.areaOf(HERO)).contains(WorldIds.AREA);
        assertThat(area().time()).isEqualTo(2.0);
        assertThat(entity(HERO).position()).as("it arrives at the stairs it left by").isEqualTo(entity(STAIRS).position());
        assertThat(entity(HERO).actionInProgress()).as("it arrives idle").isNull();

        // The level below has only its mouse now: it stands still and gives no turns.
        Area left = (Area) resource(below);
        assertThat(left.activeEntity()).isNull();
        var refused = postIntent(waitIntent(mouse, 1.0), TestSupport.serviceToken("microbe"));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(((Area) resource(below)).time()).isEqualTo(left.time());
    }

    @Test
    void theTravellersVersionContinuesAcrossEveryCrossing() throws Exception {
        setUp();
        traverse(HERO, STAIRS);
        long beforeDeparture = entity(HERO).version();
        var response = postIntent(waitIntent(WorldIds.ORBITER, 1.0), ORBITAL);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String below = store.areaOf(((Stairs) entity(STAIRS)).to()).orElseThrow();
        mouseWaits(mouseIn(below));

        long arrived = entity(HERO).version();
        assertThat(arrived).as("first version in the new area: the last one it had, plus one")
                .isEqualTo(beforeDeparture + 1);

        String pair = ((Stairs) entity(STAIRS)).to();
        traverse(HERO, pair);
        long beforeReturn = entity(HERO).version();
        assertThat(beforeReturn).isGreaterThan(arrived);
        mouseWaits(mouseIn(below));
        response = postIntent(waitIntent(WorldIds.ORBITER, 1.0), ORBITAL);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        assertThat(store.areaOf(HERO)).contains(WorldIds.AREA);
        assertThat(entity(HERO).version()).isEqualTo(beforeReturn + 1);
    }
}
