package com.pessimisticit.scarcity.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.engine.AdvanceResult;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.Engine;
import com.pessimisticit.scarcity.world.engine.Intent;
import com.pessimisticit.scarcity.world.engine.IntentRejected;
import com.pessimisticit.scarcity.world.engine.LogEntry;
import com.pessimisticit.scarcity.world.engine.LoggedDraw;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Attributes;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Npc;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A strike end to end through the engine: reach, the hit and damage draws in the log, and a kill. */
class StrikeTest {

    private static final String AREA = "area:00000000-0000-4000-8000-000000000001";
    private static final String STRIKER = "entity:00000000-0000-4000-8000-000000000002";
    private static final String TARGET = "entity:00000000-0000-4000-8000-000000000003";
    private static final String ONLOOKER = "entity:00000000-0000-4000-8000-000000000004";
    private static final Controller BRAWLER = new Controller.Service("brawler");
    private static final Catalogue CATALOGUE = Combat.standard();

    /** A striker of radius 0.3 at the origin: level 2, strength 4. */
    private static Entity striker() {
        return Prototypes.get("mouse").withName("goblin").withRadius(0.3).withPosition(new Position(0.0, 0.0))
                .withAttributes(new Attributes(2.0, 4.0, 1.0, 1.0)).withActions(List.of("strike", "wait"))
                .withController(BRAWLER).withId(STRIKER).withVersion(1);
    }

    /** A target of radius 0.2 with its centre at {@code x} on the x axis: level 1.5, {@code hp}. */
    private static Entity target(double x, double hp) {
        return Prototypes.get("mouse").withName("dog").withRadius(0.2).withPosition(new Position(x, 0.0))
                .withAttributes(new Attributes(1.5, 1.0, 1.0, 1.0)).withHp(hp).withId(TARGET).withVersion(1);
    }

    /** An idle npc far off: an area with no npc or player in it stands still (docs/actions.md, "Turns"). */
    private static Entity onlooker() {
        return new Npc("onlooker", "onlooker").withPosition(new Position(20.0, 20.0)).withMass(70_000.0).withRadius(0.3)
                .withId(ONLOOKER).withVersion(1);
    }

    private static Area area() {
        return new Area("arena", 0.0, 0, new Turn(STRIKER, BRAWLER, 0.0), List.of()).withId(AREA).withVersion(1);
    }

    private static AdvanceResult strike(Entity striker, Entity target, ScriptedRandom rng) {
        return Engine.advance(AreaSnapshot.of(area(), List.of(striker, target, onlooker())),
                Intent.of(STRIKER, Strike.specFor(TARGET), BRAWLER), rng, 1, CATALOGUE);
    }

    private static Optional<LogEntry> effect(AdvanceResult r, String type) {
        return r.log().stream().filter(e -> e.loggedEffect() != null && e.loggedEffect().type().equals(type))
                .findFirst();
    }

    private static LogEntry strikeStart(AdvanceResult r) {
        return r.log().stream().filter(e -> STRIKER.equals(e.resource()) && e.kind() == LogEntry.Kind.ACTION_START
                && e.actionInProgress().type().equals(Strike.NAME)).findFirst().orElseThrow();
    }

    @Test
    void reachIsMeasuredEdgeToEdge() {
        // Centres 0.8 apart, edges 0.8 - 0.3 - 0.2 = 0.3 apart: just within the striker's reach, its radius.
        AdvanceResult within = strike(striker(), target(0.8, 10.0), new ScriptedRandom().normal(-3.0));
        assertThat(effect(within, "melee-attack")).isPresent();
        // Edges 0.31 apart: beyond reach, though the centres are close enough for centre-to-centre reach 0.8.
        assertThatThrownBy(() -> strike(striker(), target(0.81, 10.0), new ScriptedRandom()))
                .isInstanceOf(IntentRejected.class).hasMessageContaining("beyond reach 0.3");
        // A prototype's reach overrides the radius.
        AdvanceResult longer = strike(striker().withReach(1.0), target(1.5, 10.0), new ScriptedRandom().normal(-3.0));
        assertThat(effect(longer, "melee-attack")).isPresent();
    }

    @Test
    void aStrikeAtNothingOrItselfDoesNotStart() {
        assertThatThrownBy(() -> {
            Engine.advance(AreaSnapshot.of(area(), List.of(striker(), onlooker())), Intent.of(STRIKER, Strike.specFor(STRIKER), BRAWLER),
                    new ScriptedRandom(), 1, CATALOGUE);
        }).isInstanceOf(IntentRejected.class).hasMessageContaining("itself");
        assertThatThrownBy(() -> strike(striker(), target(0.5, 10.0).withId(
                "entity:00000000-0000-4000-8000-000000000009"), new ScriptedRandom()))
                .isInstanceOf(IntentRejected.class).hasMessageContaining("no entity");
    }

    @Test
    void aHitDrawsDamageAroundStrengthAndLogsBothDraws() {
        // hit: 2.0 + 1.0 * 0.5 = 2.5 > 1.5; damage: 4.0 + 0.4 * 1.0 = 4.4
        AdvanceResult r = strike(striker(), target(0.5, 10.0), new ScriptedRandom().normal(0.5, 1.0));
        LogEntry attack = effect(r, "melee-attack").orElseThrow();
        assertThat(attack.draws()).containsExactly(new LoggedDraw("hit", new Draw.Gaussian(2.0, 1.0, null, 2.5)));
        assertThat(attack.result()).isEqualTo(WorldJson.toTree(new MeleeAttack.Result(1.5, true)));
        LogEntry damage = effect(r, "damage").orElseThrow();
        assertThat(damage.cause()).isEqualTo(attack.seq());
        assertThat(damage.draws()).containsExactly(new LoggedDraw("damage",
                new Draw.Gaussian(4.0, 0.4, new Draw.Truncate(0.0, Double.MAX_VALUE), 4.4)));
        assertThat(damage.result().get("hpAfter").asDouble()).isEqualTo(5.6, within(1e-12));
        assertThat(strikeStart(r).actionInProgress().end()).isEqualTo(Strike.DEFAULT_TIME);
    }

    @Test
    void aMissDrawsNoDamage() {
        // hit: 2.0 - 0.5 = 1.5, not higher than the target's 1.5
        AdvanceResult r = strike(striker(), target(0.5, 10.0), new ScriptedRandom().normal(-0.5));
        assertThat(effect(r, "melee-attack").orElseThrow().result())
                .isEqualTo(WorldJson.toTree(new MeleeAttack.Result(1.5, false)));
        assertThat(effect(r, "damage")).isEmpty();
    }

    @Test
    void damageIsNeverBelowZero() {
        // The damage draw's z of -50 would be 4.0 - 20 = -16: rejected, and the next, 0.0, is the damage.
        AdvanceResult r = strike(striker(), target(0.5, 10.0), new ScriptedRandom().normal(1.0, -50.0, 0.0));
        LogEntry damage = effect(r, "damage").orElseThrow();
        assertThat(damage.draws().get(0).draw()).isEqualTo(
                new Draw.Gaussian(4.0, 0.4, new Draw.Truncate(0.0, Double.MAX_VALUE), 4.0));
        assertThat(MeleeAttack.damageRoll(new Attributes(1.0, 0.5, 1.0, 1.0)).min()).isEqualTo(0.0);
    }

    @Test
    void aStrikeThatTakesTheLastHpKills() {
        AdvanceResult r = strike(striker(), target(0.5, 3.0), new ScriptedRandom().normal(1.0, 0.0));
        assertThat(effect(r, "destroy")).isPresent();
        assertThat(r.destroyed()).containsExactly(TARGET);
    }

    @Test
    void theStrikeTakesThePrototypesStrikeTime() {
        AdvanceResult r = strike(striker().withStrikeTime(0.6), target(0.5, 10.0), new ScriptedRandom().normal(-3.0));
        assertThat(strikeStart(r).actionInProgress().end()).isEqualTo(0.6);
    }
}
