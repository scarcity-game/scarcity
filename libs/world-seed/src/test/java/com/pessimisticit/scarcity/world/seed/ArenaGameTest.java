package com.pessimisticit.scarcity.world.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.Behaviour;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Faction;
import com.pessimisticit.scarcity.world.model.Factions;
import com.pessimisticit.scarcity.world.model.Player;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.WorldJsonException;
import com.pessimisticit.scarcity.world.model.rules.Spawn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArenaGameTest {

    private static final String SUB = "0b5c8a5e-3f0e-4c4e-9a0a-8f1f6f1f2f3f";

    private static ArenaGame.Options options(String json) {
        return WorldJson.fromJson(json, ArenaGame.Options.class);
    }

    private static ArenaGame.Arena make(long seed) {
        return ArenaGame.make(options("{\"dens\":[{\"prototype\":\"goblin\",\"count\":2},"
                + "{\"prototype\":\"wild-dog\",\"count\":1}]}"), SUB, new SplittableRandom(seed), "abc123");
    }

    @Test
    void theDensSpawnTheirPrototypeForBrawler() {
        ArenaGame.Arena arena = make(1);
        assertThat(arena.dens()).hasSize(3);
        List<String> spawned = new ArrayList<>();
        for (Entity den : arena.dens()) {
            assertThat(den.prototype()).isEqualTo("den");
            Spawn.Params spawn = WorldJson.fromJson(WorldJson.toJson(
                    ((Behaviour.Repeat) den.behaviour()).actionSpec().params()), Spawn.Params.class);
            assertThat(spawn.controller()).isEqualTo(ArenaGame.BRAWLER);
            assertThat(spawn.cap()).isEqualTo(ArenaGame.DEN_CAP);
            spawned.add(spawn.choices().get(0).prototype());
        }
        assertThat(spawned).containsExactly("goblin", "goblin", "wild-dog");
    }

    @Test
    void theDensStandApartInTheRoom() {
        for (long seed = 0; seed < 200; seed++) {
            ArenaGame.Arena arena = ArenaGame.make(options("{\"dens\":[{\"prototype\":\"goblin\",\"count\":4},"
                    + "{\"prototype\":\"wild-dog\",\"count\":4}]}"), SUB, new SplittableRandom(seed), "abc123");
            List<Position> spots = Stream.concat(Stream.of(arena.spectator()), arena.dens().stream())
                    .map(Entity::position).toList();
            for (int i = 0; i < spots.size(); i++) {
                Position p = spots.get(i);
                Position m = ArenaGame.MIDDLE;
                assertThat(Math.hypot(p.x() - m.x(), p.y() - m.y())).isLessThanOrEqualTo(ArenaGame.DEN_REACH);
                for (int j = 0; j < i; j++) {
                    Position q = spots.get(j);
                    assertThat(Math.hypot(p.x() - q.x(), p.y() - q.y())).isGreaterThanOrEqualTo(ArenaGame.DEN_SPACING);
                }
            }
        }
    }

    @Test
    void everySpotTriedIsADraw() {
        ArenaGame.Arena arena = make(3);
        assertThat(arena.draws()).isNotEmpty().allMatch(d -> d.name().matches("den\\[[0-2]]\\.[xy]"));
        assertThat(arena.draws().getLast().name()).isEqualTo("den[2].y");
        assertThat(arena.generation()).containsEntry("profile", "arena")
                .containsEntry("room", Map.of("shape", "regular", "sides", ArenaGame.SIDES,
                        "radius", ArenaGame.RADIUS, "middle", ArenaGame.MIDDLE));
    }

    @Test
    void theSpectatorIsAPlayerWhoStepsAndWaitsAndIsUp() {
        ArenaGame.Arena arena = make(1);
        Entity spectator = arena.spectator();
        assertThat(spectator).isInstanceOf(Player.class);
        assertThat(spectator.controller()).isEqualTo(new Controller.User(SUB));
        assertThat(spectator.actions()).containsExactly("step", "wait");
        assertThat(spectator.stepReach()).isEqualTo(5.0);
        assertThat(spectator.vision()).isEqualTo(10.0);
        assertThat(spectator.damageable()).isFalse();
        assertThat(spectator.overlap()).isTrue();
        assertThat(arena.area().activeEntity().entity()).isEqualTo(spectator.id());
        assertThat(arena.area().pending()).isEmpty();
    }

    @Test
    void theFightingFactionsAreHostileToEachOtherNotToThePlayer() {
        Map<String, Faction> factions = make(1).game().factions();
        assertThat(factions).containsOnlyKeys("goblin", "beast");
        assertThat(factions.get("goblin").hostileTo()).containsExactly("beast");
        assertThat(factions.get("beast").hostileTo()).containsExactly("goblin");
        assertThat(factions.values()).noneMatch(f -> f.hostileTo().contains(Factions.PLAYER));
    }

    @Test
    void everyResourceHasANewId() {
        ArenaGame.Arena a = make(1);
        ArenaGame.Arena b = make(2);
        assertThat(a.game().id()).startsWith("game:").isNotEqualTo(b.game().id()).isNotEqualTo(WorldIds.GAME);
        assertThat(a.area().id()).startsWith("area:").isNotEqualTo(b.area().id());
        List<String> ids = Stream.of(List.of(a.spectator()), a.dens(), a.walls()).flatMap(List::stream)
                .map(Entity::id).toList();
        assertThat(ids).hasSize(1 + 3 + ArenaGame.SIDES).doesNotHaveDuplicates().allMatch(id -> id.startsWith("entity:"));
        assertThat(a.game().seed().build()).isEqualTo("abc123");
    }

    @Test
    void everyArenaMobFights() {
        assertThat(ArenaGame.MOBS).allMatch(p -> Prototypes.get(p).actions().contains("strike"));
    }

    @Test
    void badOptionsAreRefused() {
        assertThatThrownBy(() -> options("{\"dens\":[]}")).hasMessageContaining("at least one");
        assertThatThrownBy(() -> options("{\"dens\":[{\"prototype\":\"pillar\",\"count\":1}]}"))
                .hasMessageContaining("not an arena mob");
        assertThatThrownBy(() -> options("{\"dens\":[{\"prototype\":\"goblin\",\"count\":0}]}"))
                .hasMessageContaining("count");
        assertThatThrownBy(() -> options("{\"dens\":[{\"prototype\":\"goblin\",\"count\":1},"
                + "{\"prototype\":\"goblin\",\"count\":1}]}")).hasMessageContaining("twice");
        assertThatThrownBy(() -> options("{}")).isInstanceOf(WorldJsonException.class);
        assertThatThrownBy(() -> options("{\"dens\":[],\"extra\":1}")).isInstanceOf(WorldJsonException.class);
    }
}
