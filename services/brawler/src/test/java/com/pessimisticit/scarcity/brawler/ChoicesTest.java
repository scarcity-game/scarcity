package com.pessimisticit.scarcity.brawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.pessimisticit.scarcity.combat.Strike;
import com.pessimisticit.scarcity.controller.Choice;
import com.pessimisticit.scarcity.controller.TurnFrame;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Faction;
import com.pessimisticit.scarcity.world.model.Factions;
import com.pessimisticit.scarcity.world.model.Player;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Step;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * Exactly one choice is feasible on every turn: strike the quarry in reach, step toward it out of
 * reach, wait with nobody to fight. Whom it fights is the game's faction relations and its foes.
 */
class ChoicesTest {

    static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    static final String SELF = "entity:00000000-0000-4000-8000-000000000021";
    static final String DOG = "entity:00000000-0000-4000-8000-000000000022";
    static final String KIN = "entity:00000000-0000-4000-8000-000000000023";
    static final String HERO = "entity:00000000-0000-4000-8000-000000000024";
    static final String FAR_DOG = "entity:00000000-0000-4000-8000-000000000025";
    private static final Controller BRAWLER = new Controller.Service("brawler");
    /** An arena's relations: goblins and beasts fight each other, nobody fights players. */
    static final Map<String, Faction> ARENA = Map.of("goblin", new Faction(List.of("beast")),
            "beast", new Faction(List.of("goblin")));

    private final Quarry quarry = new Quarry();
    private final BrawlerProperties properties = new BrawlerProperties(new BrawlerProperties.StepSettings(1.0),
            new BrawlerProperties.WaitSettings(1.0));
    private final Roaming roaming = new Roaming(properties, RandomGenerator.of("L64X128MixRandom"));
    private final List<Choice> choices = List.of(new StrikeChoice(quarry),
            new StepChoice(quarry, roaming, properties), new WaitChoice(quarry, roaming, properties));

    /** A goblin of the prototype at {@code (x, y)}: radius 0.25, reach its radius. */
    static Entity goblin(String id, double x, double y) {
        return Prototypes.get("goblin").withPosition(new Position(x, y)).withHp(8.0)
                .withActions(List.of("strike", "step", "wait")).withController(BRAWLER).withId(id).withVersion(1);
    }

    static Entity dog(String id, double x, double y) {
        return Prototypes.get("wild-dog").withPosition(new Position(x, y)).withHp(6.0)
                .withActions(List.of("strike", "step", "wait")).withController(BRAWLER).withId(id).withVersion(1);
    }

    private static Entity hero(double x, double y) {
        return new Player("goblin", "hero").withPosition(new Position(x, y)).withMass(70_000.0).withRadius(0.3)
                .withDamageable(true).withHp(10.0).withId(HERO).withVersion(1);
    }

    private static Choice.Situation situation(Entity self, Map<String, Faction> factions, Entity... others) {
        Map<String, Entity> entities = new LinkedHashMap<>();
        entities.put(self.id(), self);
        for (Entity e : others) {
            entities.put(e.id(), e);
        }
        AreaView view = AreaView.of(AREA, new Area(AREA, 0.0), entities);
        return new Choice.Situation(new TurnFrame(AREA, self.id(), 1, 0.0), self, view, factions);
    }

    private List<Choice.Plan> feasible(Choice.Situation situation) {
        return choices.stream().map(c -> c.plan(situation)).filter(Choice.Plan::feasible).toList();
    }

    private Choice.Plan only(Choice.Situation situation) {
        List<Choice.Plan> plans = feasible(situation);
        assertThat(plans).hasSize(1);
        return plans.getFirst();
    }

    @Test
    void aQuarryInReachIsStruck() {
        // gap: 0.6 - 0.25 - 0.22 = 0.13, within the goblin's reach (its radius, 0.25)
        Choice.Plan plan = only(situation(goblin(SELF, 0.0, 0.0), ARENA, dog(DOG, 0.6, 0.0)));
        assertThat(plan.actionSpec()).isEqualTo(Strike.specFor(DOG));
    }

    @Test
    void aQuarryOutOfReachIsChasedAtMostTheMaxDistance() {
        Entity self = goblin(SELF, 0.0, 0.0);
        Choice.Plan plan = only(situation(self, ARENA, dog(DOG, 4.0, 0.0)));
        assertThat(plan.actionSpec().type()).isEqualTo(Step.INSTANCE.name());
        Position to = new Position(plan.actionSpec().params().path("to").path("x").asDouble(),
                plan.actionSpec().params().path("to").path("y").asDouble());
        assertThat(to.x()).isCloseTo(Math.min(1.0, Step.reach(self)), offset(1e-9));
        assertThat(to.y()).isCloseTo(0.0, offset(1e-9));
    }

    @Test
    void theLastStepStopsInReach() {
        Entity self = goblin(SELF, 0.0, 0.0);
        Entity dog = dog(DOG, 1.2, 0.0);
        Position to = stepTo(only(situation(self, ARENA, dog)));
        Entity there = self.withPosition(to);
        assertThat(Quarry.inReach(there, dog)).isTrue();
        assertThat(Strike.gap(there, dog)).isPositive();
    }

    @Test
    void itGoesForTheNearestOfThoseItIsHostileTo() {
        Choice.Plan plan = only(situation(goblin(SELF, 0.0, 0.0), ARENA, dog(FAR_DOG, 0.0, 3.0),
                dog(DOG, 0.6, 0.0)));
        assertThat(plan.actionSpec()).isEqualTo(Strike.specFor(DOG));
    }

    @Test
    void kinOfTheSameFactionAreNeverTargets() {
        Choice.Plan plan = only(situation(goblin(SELF, 0.0, 0.0), ARENA, goblin(KIN, 0.6, 0.0)));
        assertThat(plan.actionSpec().type()).isEqualTo("step");
    }

    @Test
    void aSideTheFactionIsNotHostileToIsLeftAlone() {
        // The arena leaves players alone; the standard relations make goblins attack them on sight.
        assertThat(only(situation(goblin(SELF, 0.0, 0.0), ARENA, hero(0.6, 0.0))).actionSpec().type())
                .isEqualTo("step");
        assertThat(only(situation(goblin(SELF, 0.0, 0.0), Factions.standard(), hero(0.6, 0.0))).actionSpec())
                .isEqualTo(Strike.specFor(HERO));
        assertThat(only(situation(goblin(SELF, 0.0, 0.0), Factions.standard(), dog(DOG, 0.6, 0.0)))
                .actionSpec().type()).isEqualTo("step");
    }

    @Test
    void aNeutralStrikesBackAtAFoe() {
        Entity self = goblin(SELF, 0.0, 0.0);
        Map<String, Faction> none = Map.of();
        assertThat(only(situation(self, none, goblin(KIN, 0.6, 0.0))).actionSpec().type()).isEqualTo("step");
        assertThat(only(situation(self.withFoe(KIN), none, goblin(KIN, 0.6, 0.0))).actionSpec())
                .isEqualTo(Strike.specFor(KIN));
    }

    @Test
    void withNobodyInRangeItRoamsAndReportsTheDraws() {
        Entity self = goblin(SELF, 0.0, 0.0);
        Choice.Plan plan = only(situation(self, ARENA));
        double limit = Math.min(1.0, Step.reach(self));
        assertThat(stepTo(plan).distanceTo(self.position())).isBetween(limit / 2 - 1e-9, limit + 1e-9);
        assertThat(plan.draws()).containsKeys("heading.1", "distance.1");
        assertThat(plan.draws().get("heading.1")).isInstanceOf(Draw.Uniform.class);
    }

    @Test
    void withNobodyInRangeAndNoSpeedItWaits() {
        Entity still = goblin(SELF, 0.0, 0.0).withSpeed(null);
        assertThat(only(situation(still, ARENA)).actionSpec().type()).isEqualTo("wait");
    }

    @Test
    void fightersFromDensFurtherApartThanTheRangeMeetAndFight() {
        // 15 m apart, past the 8 m range: neither sees the other until roaming brings them near,
        // in a ring of walls 10 m in radius (the arena's are 20 m).
        Map<String, Entity> fighters = new LinkedHashMap<>();
        fighters.put(SELF, goblin(SELF, -7.5, 0.0));
        fighters.put(DOG, dog(DOG, 7.5, 0.0));
        boolean fought = false;
        for (int turn = 0; turn < 3000 && !fought; turn++) {
            for (String id : List.copyOf(fighters.keySet())) {
                Entity self = fighters.get(id);
                Map<String, Entity> inRange = new LinkedHashMap<>(fighters);
                inRange.values().removeIf(e -> e.position().distanceTo(self.position()) > 8.0);
                AreaView view = AreaView.of(AREA, new Area(AREA, 0.0), inRange);
                Choice.Situation situation = new Choice.Situation(new TurnFrame(AREA, id, 1, 0.0), self, view, ARENA);
                Choice.Plan plan = only(situation);
                if (plan.actionSpec().type().equals("strike")) {
                    fought = true;
                } else if (plan.actionSpec().type().equals("step")) {
                    Position to = stepTo(plan);
                    // Walls: a ring of radius 10 m that nobody leaves.
                    if (Math.hypot(to.x(), to.y()) <= 10.0) {
                        fighters.put(id, self.withPosition(to));
                    }
                }
            }
        }
        assertThat(fought).as("they met and one struck the other").isTrue();
    }

    @Test
    void whatCannotBeStruckIsNoQuarry() {
        Entity pillar = Prototypes.get("pillar").withPosition(new Position(0.8, 0.0))
                .withFaction("beast").withId(DOG).withVersion(1);
        assertThat(only(situation(goblin(SELF, 0.0, 0.0), ARENA, pillar)).actionSpec().type()).isEqualTo("step");
    }

    private static Position stepTo(Choice.Plan plan) {
        assertThat(plan.actionSpec().type()).isEqualTo(Step.INSTANCE.name());
        return new Position(plan.actionSpec().params().path("to").path("x").asDouble(),
                plan.actionSpec().params().path("to").path("y").asDouble());
    }
}
