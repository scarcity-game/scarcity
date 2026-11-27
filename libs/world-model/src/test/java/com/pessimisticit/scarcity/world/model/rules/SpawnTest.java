package com.pessimisticit.scarcity.world.model.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.EntityTemplate;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code spawn}, {@code spawn-wait} and {@code spawn-make}: a den's cycle (#453). */
class SpawnTest {

    private static final String DEN = "entity:00000000-0000-4000-8000-0000000000d1";
    private static final String MOB = "entity:00000000-0000-4000-8000-0000000000d2";
    private static final Controller BRAWLER = new Controller.Service("brawler");
    private static final Spawn.Params PARAMS = params(List.of(new Spawn.Choice(1.0, "mouse"), new Spawn.Choice(3.0, "pebble")));
    private static final SpawnMake MAKE = new SpawnMake(Create.INSTANCE);

    private static Spawn.Params params(List<Spawn.Choice> choices) {
        return new Spawn.Params(choices, 2, BRAWLER, new Spawn.Interval(4.0, 1.0, 1.0, 8.0), null);
    }

    private static Entity den(double x, double y) {
        return new EntityTemplate("den").at(new Position(x, y));
    }

    private static Map<String, Entity> room(Entity... others) {
        Map<String, Entity> all = new HashMap<>(TestArea.walls());
        all.put(DEN, den(2.0, 4.0));
        for (int i = 0; i < others.length; i++) {
            all.put("entity:00000000-0000-4000-8000-0000000000e" + i, others[i]);
        }
        return all;
    }

    private static FakeContext make(Map<String, Entity> entities, ScriptedRandom random, Spawn.Params spawn) {
        FakeContext ctx = new FakeContext(TestArea.ROOM, entities, random);
        MAKE.run(new SpawnMake.Params(DEN, spawn), ctx);
        return ctx;
    }

    private static Spawn.Params mice() {
        return params(List.of(new Spawn.Choice(1.0, "mouse")));
    }

    private static Entity created(FakeContext ctx) {
        assertThat(ctx.triggered).hasSize(1);
        return ((Create.Params) ctx.triggered.get(0).params()).entity();
    }

    private static List<String> names(FakeContext ctx) {
        return ctx.draws.stream().map(FakeContext.Drawn::forWhat).toList();
    }

    @Test
    void theWaitIsADrawnIntervalAndStartsTheWaitingAction() {
        FakeContext ctx = new FakeContext(TestArea.ROOM, room(), new ScriptedRandom().normal(-1.5));
        SpawnWait.INSTANCE.run(new SpawnWait.Params(DEN, PARAMS), ctx);
        Draw.Gaussian expected = new Draw.Gaussian(4.0, 1.0, new Draw.Truncate(1.0, 8.0), 2.5);
        assertThat(ctx.draws).containsExactly(new FakeContext.Drawn("interval", expected));
        assertThat(ctx.result).isEqualTo(new SpawnWait.Result(2.5));
        assertThat(ctx.started).hasSize(1);
        assertThat(ctx.started.get(0).id()).isEqualTo(DEN);
        ActionSpec spec = ctx.started.get(0).actionSpec();
        assertThat(spec.type()).isEqualTo("spawn");
        assertThat(WorldJson.fromJson(spec.params(), Spawn.Params.class, "spawn").waited()).isEqualTo(2.5);
    }

    @Test
    void aCycleIsAnArmingActionThenAWaitingOne() {
        Spawn spawn = new Spawn(SpawnWait.INSTANCE, MAKE);
        Entity den = den(2.0, 4.0);
        Actor actor = TestArea.actor(DEN, den, Map.of());
        assertThat(spawn.duration(PARAMS, den)).isEqualTo(Spawn.ARM);
        assertThat(spawn.onEnd(PARAMS, actor)).isEmpty();
        assertThat(spawn.onStart(PARAMS, actor)).extracting(EffectCall::type).containsExactly("spawn-wait");
        Spawn.Params waiting = PARAMS.waiting(2.5);
        assertThat(spawn.duration(waiting, den)).isEqualTo(2.5);
        assertThat(spawn.onStart(waiting, actor)).isEmpty();
        assertThat(spawn.onEnd(waiting, actor)).extracting(EffectCall::type).containsExactly("spawn-make");
    }

    @Test
    void theStandardCatalogueHasTheDensRulesAndStartsItsAction() {
        Catalogue catalogue = Catalogue.standard();
        assertThat(catalogue.actionNames()).contains("spawn");
        assertThat(catalogue.effectNames()).contains("spawn-wait", "spawn-make");
        Actor actor = TestArea.actor(DEN, den(2.0, 4.0), Map.of());
        assertThat(catalogue.start(Spawn.specFor(PARAMS), actor).duration(actor.entity())).isEqualTo(Spawn.ARM);
    }

    @Test
    void theParamsAreChecked() {
        Spawn.Interval interval = new Spawn.Interval(4.0, 1.0, 1.0, 8.0);
        List<Spawn.Choice> choices = List.of(new Spawn.Choice(1.0, "mouse"));
        assertThatThrownBy(() -> new Spawn.Params(choices, 0, BRAWLER, interval, null)).hasMessageContaining("cap");
        assertThatThrownBy(() -> new Spawn.Params(List.of(), 1, BRAWLER, interval, null)).hasMessageContaining("choices");
        assertThatThrownBy(() -> new Spawn.Choice(0.0, "mouse")).hasMessageContaining("weight");
        assertThatThrownBy(() -> new Spawn.Choice(1.0, "no-such-prototype")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Spawn.Interval(4.0, 1.0, 0.0, 8.0)).hasMessageContaining("min");
        assertThatThrownBy(() -> new Spawn.Interval(4.0, 1.0, 5.0, 3.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePickIsALoggedCategoricalDrawAndTheMobTakesItsValuesFromTheDraws() {
        Spawn.Params spawn = params(List.of(new Spawn.Choice(3.0, "pebble"), new Spawn.Choice(1.0, "mouse")));
        ScriptedRandom random = new ScriptedRandom().uniform(0.9, 0.0)
                .normal(1.0, 2.0, -1.0, 1.0, -1.0, 0.5, 0.0);
        FakeContext ctx = make(room(), random, spawn);
        assertThat(ctx.draws.get(0)).isEqualTo(new FakeContext.Drawn("pick", new Draw.Categorical(List.of(3.0, 1.0), 0.9, 1)));
        assertThat(names(ctx)).containsExactly("pick", "radius", "spot", "mass", "speed", "level", "strength",
                "dexterity", "intelligence");
        Entity mob = created(ctx);
        assertThat(mob.prototype()).isEqualTo("mouse");
        assertThat(mob.radius()).isEqualTo(draw(ctx, 1));
        assertThat(mob.mass()).isEqualTo(draw(ctx, 3));
        assertThat(mob.speed()).isEqualTo(draw(ctx, 4));
        assertThat(mob.attributes().level()).isEqualTo(draw(ctx, 5));
        assertThat(mob.attributes().intelligence()).isEqualTo(draw(ctx, 8));
        assertThat(mob.radius()).isCloseTo(0.04 * 1.05, within(1e-12));
        assertThat(mob.spawner()).isEqualTo(DEN);
        assertThat(mob.controller()).isEqualTo(BRAWLER);
        assertThat(mob.faction()).isEqualTo("vermin");
        assertThat(mob.actions()).isEqualTo(new EntityTemplate("mouse").at(new Position(0, 0)).actions());
    }

    private static double draw(FakeContext ctx, int index) {
        return ((Draw.Gaussian) ctx.draws.get(index).draw()).value();
    }

    @Test
    void theMobIsPlacedOnTheRingAtTheDrawnAngle() {
        FakeContext ctx = make(room(), new ScriptedRandom().uniform(0.0, 0.25).normal(0, 0, 0, 0, 0, 0, 0), mice());
        Draw.Uniform spot = (Draw.Uniform) ctx.draws.get(2).draw();
        assertThat(spot.min()).isEqualTo(0.0);
        assertThat(spot.max()).isCloseTo(2 * Math.PI, within(1e-12));
        assertThat(spot.value()).isCloseTo(Math.PI / 2, within(1e-12));
        Entity mob = created(ctx);
        double distance = 0.5 + mob.radius() + Placement.MARGIN;
        assertThat(mob.position().x()).isCloseTo(2.0, within(1e-9));
        assertThat(mob.position().y()).isCloseTo(4.0 + distance, within(1e-9));
        assertThat(ctx.result).isEqualTo(new SpawnMake.Made("mouse", mob.position()));
    }

    @Test
    void nothingIsMadeAtTheCapAndNothingIsDrawn() {
        Entity first = new EntityTemplate("mouse").at(new Position(3.0, 4.0)).withSpawner(DEN);
        Entity second = new EntityTemplate("mouse").at(new Position(4.0, 6.0)).withSpawner(DEN);
        FakeContext ctx = make(room(first, second), new ScriptedRandom(), mice());
        assertThat(ctx.result).isEqualTo(new SpawnMake.Skipped("at cap"));
        assertThat(ctx.draws).isEmpty();
        assertThat(ctx.triggered).isEmpty();
    }

    @Test
    void mobsOfAnotherDenDontCount() {
        String other = "entity:00000000-0000-4000-8000-0000000000d9";
        Entity first = new EntityTemplate("mouse").at(new Position(3.0, 4.0)).withSpawner(other);
        Entity second = new EntityTemplate("mouse").at(new Position(4.0, 6.0));
        FakeContext ctx = make(room(first, second), new ScriptedRandom().uniform(0.0, 0.25).normal(0, 0, 0, 0, 0, 0, 0), mice());
        assertThat(ctx.result).isInstanceOf(SpawnMake.Made.class);
    }

    @Test
    void itSpawnsAgainWhenOneOfItsMobsHasDied() {
        Entity first = new EntityTemplate("mouse").at(new Position(3.0, 4.0)).withSpawner(DEN);
        Entity second = new EntityTemplate("mouse").at(new Position(4.0, 6.0)).withSpawner(DEN);
        Map<String, Entity> entities = room(first, second);
        assertThat(make(entities, new ScriptedRandom(), mice()).result).isEqualTo(new SpawnMake.Skipped("at cap"));
        entities.remove("entity:00000000-0000-4000-8000-0000000000e1");
        FakeContext ctx = make(entities, new ScriptedRandom().uniform(0.0, 0.25).normal(0, 0, 0, 0, 0, 0, 0), mice());
        assertThat(ctx.result).isInstanceOf(SpawnMake.Made.class);
        assertThat(created(ctx).spawner()).isEqualTo(DEN);
    }

    @Test
    void aSpotInsideAWallIsRefusedAndTheNextAngleIsTried() {
        Map<String, Entity> entities = new HashMap<>(TestArea.walls());
        entities.put(DEN, den(0.3, 4.0));
        // angle pi is into the west wall (x -0.5 to 0), angle 0 is free
        FakeContext ctx = make(entities, new ScriptedRandom().uniform(0.0, 0.5, 0.0).normal(0, 0, 0, 0, 0, 0, 0), mice());
        assertThat(ctx.draws.stream().filter(d -> d.forWhat().equals("spot"))).hasSize(2);
        assertThat(created(ctx).position().x()).isGreaterThan(0.3);
        assertThat(ctx.result).isInstanceOf(SpawnMake.Made.class);
    }

    @Test
    void aDenBoxedInByEntitiesMakesNothingAfterEightRecordedAngles() {
        Entity[] posts = new Entity[8];
        double[] us = new double[8];
        for (int k = 0; k < 8; k++) {
            double angle = k * Math.PI / 4;
            posts[k] = TestArea.state("""
                    {"resourceKind":"entity","entityKind":"fixed","prototype":"post","name":"post","position":{"x":%s,"y":%s},"radius":0.3}"""
                    .formatted(2.0 + 0.9 * Math.cos(angle), 4.0 + 0.9 * Math.sin(angle)));
            us[k] = k / 8.0;
        }
        FakeContext ctx = make(room(posts), new ScriptedRandom().uniform(0.0).uniform(us).normal(0.0), mice());
        assertThat(ctx.result).isEqualTo(new SpawnMake.Skipped("no room"));
        assertThat(names(ctx)).containsExactly("pick", "radius", "spot", "spot", "spot", "spot", "spot", "spot", "spot", "spot");
        assertThat(ctx.triggered).isEmpty();
    }

    @Test
    void aSpotBehindAThinWallIsNotReachable() {
        Entity thin = TestArea.wall(2.5, 3.0, 2.52, 5.0);
        FakeContext ctx = make(room(thin), new ScriptedRandom().uniform(0.0, 0.0, 0.5).normal(0, 0, 0, 0, 0, 0, 0), mice());
        // angle 0 puts the mob at x = 2.59, beyond the thin wall: refused; angle pi is free
        assertThat(ctx.draws.stream().filter(d -> d.forWhat().equals("spot"))).hasSize(2);
        assertThat(created(ctx).position().x()).isLessThan(2.0);
    }
}
