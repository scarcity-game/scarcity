package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** A creature's attributes and faction (#450): read, written, and varied around its prototype's. */
class AttributesTest {

    @Test
    void theMouseHasAttributesAndAFactionFromItsPrototype() {
        Entity mouse = Prototypes.get("mouse");
        assertThat(mouse.attributes()).isEqualTo(new Attributes(1.0, 0.5, 4.0, 1.0));
        assertThat(mouse.faction()).isEqualTo("vermin");
        Entity made = new EntityTemplate("mouse").at(new Position(1.0, 2.0));
        assertThat(made.faction()).isEqualTo("vermin");
        assertThat(made.attributes()).isEqualTo(mouse.attributes());
    }

    @Test
    void theGoblinAndTheWildDogReadWithTheirFactionsAndAttributes() {
        Entity goblin = Prototypes.get("goblin");
        Entity dog = Prototypes.get("wild-dog");
        assertThat(goblin.faction()).isEqualTo("goblin");
        assertThat(dog.faction()).isEqualTo("beast");
        assertThat(goblin.attributes()).isEqualTo(new Attributes(2.0, 2.2, 5.0, 3.0));
        assertThat(dog.attributes()).isEqualTo(new Attributes(1.8, 3.0, 8.0, 1.0));
        assertThat(goblin.actions()).containsExactly("step", "wait", "strike");
        assertThat(dog.actions()).containsExactly("step", "wait", "strike");
        assertThat(dog.speed()).isGreaterThan(goblin.speed());
        assertThat(dog.mass()).isLessThan(goblin.mass());
        assertThat(dog.attributes().level()).isLessThan(goblin.attributes().level());
    }

    @Test
    void anEntityWithoutThemReadsAsBefore() {
        Entity pebble = Prototypes.get("pebble");
        assertThat(pebble.attributes()).isNull();
        assertThat(pebble.faction()).isNull();
        assertThat(WorldJson.toTree(pebble).has("level")).isFalse();
        assertThat(WorldJson.toTree(pebble).has("faction")).isFalse();
    }

    @Test
    void eachAttributeIsDrawnAroundThePrototypesAndRecorded() {
        Attributes base = Prototypes.get("mouse").attributes();
        Map<String, Draw.Gaussian> draws = new LinkedHashMap<>();
        Attributes varied = base.vary(new SplittableRandom(3), draws::put);

        assertThat(draws.keySet()).containsExactly("level", "strength", "dexterity", "intelligence");
        assertThat(varied).isEqualTo(new Attributes(draws.get("level").value(), draws.get("strength").value(),
                draws.get("dexterity").value(), draws.get("intelligence").value()));
        assertThat(List.of(draws.get("level").mean(), draws.get("strength").mean(), draws.get("dexterity").mean(),
                draws.get("intelligence").mean())).containsExactly(1.0, 0.5, 4.0, 1.0);
        assertThat(draws.get("dexterity").sd()).isEqualTo(4.0 * Draws.VARIATION);
        assertThat(draws.get("strength").truncate()).isEqualTo(new Draw.Truncate(Double.MIN_VALUE, 1.0));
        assertThat(varied).isNotEqualTo(base);
    }

    @Test
    void theyAreWrittenFlatAndReadBack() {
        Entity mouse = Prototypes.get("mouse").withAttributes(new Attributes(1.5, 0.6, 3.9, 1.1)).withFaction("pets");
        String json = WorldJson.toJson(mouse);
        assertThat(WorldJson.toTree(mouse).get("strength").asDouble()).isEqualTo(0.6);
        assertThat(WorldJson.toTree(mouse).get("faction").asString()).isEqualTo("pets");
        Entity read = WorldJson.fromJson(json, Entity.class);
        assertThat(read.attributes()).isEqualTo(new Attributes(1.5, 0.6, 3.9, 1.1));
        assertThat(read.faction()).isEqualTo("pets");
        assertThat(read.withAttributes(null).withFaction(null).attributes()).isNull();
    }

    @Test
    void reachAndStrikeTimeAreOptionalAndPositive() {
        Entity mouse = Prototypes.get("mouse");
        assertThat(mouse.reach()).isNull();
        assertThat(mouse.strikeTime()).isNull();
        Entity read = WorldJson.fromJson(WorldJson.toJson(mouse.withReach(0.6).withStrikeTime(0.8)), Entity.class);
        assertThat(read.reach()).isEqualTo(0.6);
        assertThat(read.strikeTime()).isEqualTo(0.8);
        assertThatThrownBy(() -> mouse.withReach(0.0)).hasMessageContaining("reach");
        assertThatThrownBy(() -> mouse.withStrikeTime(-1.0)).hasMessageContaining("strikeTime");
    }

    @Test
    void anEntityHasAllFourOrNoneAndEachIsPositive() {
        String mouse = WorldJson.toJson(Prototypes.get("mouse"));
        assertThatThrownBy(() -> WorldJson.fromJson(mouse.replace("\"dexterity\":4.0,", ""), Entity.class))
                .hasMessageContaining("level, strength, dexterity and intelligence");
        assertThatThrownBy(() -> WorldJson.fromJson(mouse.replace("\"level\":1.0", "\"level\":0.0"), Entity.class))
                .hasMessageContaining("level");
        assertThatThrownBy(() -> new Attributes(1.0, -1.0, 1.0, 1.0)).hasMessageContaining("strength");
        assertThatThrownBy(() -> WorldJson.fromJson(mouse.replace("\"vermin\"", "\"\""), Entity.class))
                .hasMessageContaining("faction");
    }
}
