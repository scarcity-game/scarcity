package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Faction relations and foes (docs/resources.md, "Factions"). */
class FactionsTest {

    private static final String GOBLIN = "entity:00000000-0000-4000-8000-000000000011";
    private static final String DOG = "entity:00000000-0000-4000-8000-000000000012";
    private static final String MOUSE = "entity:00000000-0000-4000-8000-000000000013";
    private static final String HERO = "entity:00000000-0000-4000-8000-000000000014";

    private static Entity goblin() {
        return Prototypes.get("goblin").withId(GOBLIN);
    }

    private static Entity dog() {
        return Prototypes.get("wild-dog").withId(DOG);
    }

    private static Entity mouse() {
        return Prototypes.get("mouse").withId(MOUSE);
    }

    private static Entity hero() {
        return new Player("mouse", "hero").withFaction("goblin").withId(HERO);
    }

    @Test
    void theStandardRelationsAreTheFile() {
        assertThat(Factions.standard()).containsOnlyKeys("goblin", "beast", "demon", "vermin");
        assertThat(Factions.standard().get("goblin").hostileTo()).containsExactly(Factions.PLAYER);
        assertThat(Factions.standard().get("demon").hostileTo()).containsExactly(Factions.PLAYER, "angel");
        assertThat(Factions.standard().get("vermin")).isEqualTo(Faction.NEUTRAL);
    }

    @Test
    void aPlayerIsOnThePlayersSideWhateverItsFaction() {
        assertThat(Factions.side(hero())).isEqualTo(Factions.PLAYER);
        assertThat(Factions.side(goblin())).isEqualTo("goblin");
        assertThat(Factions.side(Prototypes.get("pillar"))).isNull();
    }

    @Test
    void aFactionAttacksTheSidesItIsHostileToAndNoOthers() {
        Map<String, Faction> standard = Factions.standard();
        assertThat(Factions.hostile(standard, goblin(), hero())).isTrue();
        assertThat(Factions.hostile(standard, goblin(), dog())).isFalse();
        assertThat(Factions.hostile(standard, goblin(), goblin().withId(DOG))).isFalse();
        assertThat(Factions.hostile(standard, mouse(), hero())).isFalse();
        Map<String, Faction> arena = Map.of("goblin", new Faction(List.of("beast")));
        assertThat(Factions.hostile(arena, goblin(), dog())).isTrue();
        assertThat(Factions.hostile(arena, goblin(), hero())).isFalse();
        assertThat(Factions.hostile(arena, dog(), goblin())).as("beast isn't listed: neutral").isFalse();
    }

    @Test
    void aNeutralStrikesBackAtItsFoes() {
        Entity bitten = mouse().withFoe(DOG);
        assertThat(Factions.hostile(Factions.standard(), bitten, dog())).isTrue();
        assertThat(Factions.hostile(Factions.standard(), bitten, goblin())).isFalse();
    }

    @Test
    void foesAreKeptInOrderOnceEach() {
        Entity bitten = mouse().withFoe(DOG).withFoe(GOBLIN);
        assertThat(bitten.withFoe(DOG)).isSameAs(bitten);
        assertThat(bitten.foes()).containsExactly(DOG, GOBLIN);
        Entity read = WorldJson.fromJson(WorldJson.toJson(bitten), Entity.class);
        assertThat(read.foes()).containsExactly(DOG, GOBLIN);
        assertThat(WorldJson.toJson(mouse())).doesNotContain("foes");
        assertThatThrownBy(() -> mouse().withFoe("area:00000000-0000-4000-8000-000000000001"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGameHasItsOwnRelationsOrTheStandardOnes() {
        Game plain = new Game("Dive game");
        assertThat(plain.factions()).isEmpty();
        assertThat(plain.relations()).isEqualTo(Factions.standard());
        assertThat(WorldJson.toJson(plain)).doesNotContain("factions");

        Map<String, Faction> arena = Map.of("goblin", new Faction(List.of("beast")),
                "beast", new Faction(List.of("goblin")));
        Game game = plain.withFactions(arena);
        Game read = WorldJson.fromJson(WorldJson.toJson(game), Game.class);
        assertThat(read.factions()).isEqualTo(arena);
        assertThat(read.relations()).isEqualTo(arena);
    }

    @Test
    void aSideListedTwiceIsAnError() {
        assertThatThrownBy(() -> new Faction(List.of("player", "player")))
                .hasMessage("hostileTo: 'player' is listed twice");
    }
}
