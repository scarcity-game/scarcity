package com.pessimisticit.scarcity.world.model.rules;

import static com.pessimisticit.scarcity.world.model.rules.TestArea.ORBITER;
import static com.pessimisticit.scarcity.world.model.rules.TestArea.PILLAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code line-attack}: the number checks of the old PebbleThrowTest, adapted to {@code at}. */
class LineAttackTest {

    private static final Position FROM = new Position(8.0, 4.0);
    private static final Position PILLAR_CENTRE = new Position(5.0, 4.0);

    private static FakeContext attack(Map<String, EntityState> entities, Position at, double scatter) {
        FakeContext ctx = new FakeContext(TestArea.ROOM, entities, new ScriptedRandom().normal(scatter));
        LineAttack.INSTANCE.run(new LineAttack.Params(ORBITER, FROM, at, 0.2, TestArea.projectile()), ctx);
        return ctx;
    }

    private static LineAttack.Result result(FakeContext ctx) {
        return (LineAttack.Result) ctx.result;
    }

    private static EntityState post(double x, double y) {
        return TestArea.state("""
                {"kind":"fixed","name":"post","position":{"x":%s,"y":%s},"radius":0.5}""".formatted(x, y));
    }

    @Test
    void aStraightThrowHitsThePillarsNearSide() {
        FakeContext ctx = attack(TestArea.seed(), PILLAR_CENTRE, 0.0);
        assertThat(result(ctx).hit()).isEqualTo(PILLAR);
        assertThat(result(ctx).direction()).isCloseTo(Math.PI, within(1e-9));
        assertThat(result(ctx).point().x()).isCloseTo(5.5, within(1e-9));
        assertThat(result(ctx).point().y()).isCloseTo(4.0, within(1e-9));
        assertThat(ctx.draws).containsExactly(new FakeContext.Drawn("scatter", new Draw.Gaussian(0.0, 0.2, null, 0.0)));
    }

    @Test
    void aScatteredThrowMissesAndMeetsTheWall() {
        // 1.5 * sd 0.2 = 0.3 rad off: the line passes 0.89 from the pillar's centre (radius 0.5)
        FakeContext ctx = attack(TestArea.seed(), PILLAR_CENTRE, 1.5);
        assertThat(result(ctx).hit()).isNull();
        assertThat(result(ctx).direction()).isCloseTo(Math.PI + 0.3, within(1e-9));
        assertThat(result(ctx).point().x()).isCloseTo(0.0, within(1e-9));
        assertThat(result(ctx).point().y()).isCloseTo(4.0 - 8.0 * Math.tan(0.3), within(1e-9));
        Draw.Gaussian scatter = (Draw.Gaussian) ctx.draws.get(0).draw();
        assertThat(scatter.sd()).isEqualTo(0.2);
        assertThat(scatter.value()).isCloseTo(0.3, within(1e-12));
        assertThat(WorldJson.toTree(result(ctx)).get("hit").isNull()).isTrue();
    }

    @Test
    void theNearestEntryWinsWhereverTheAimIs() {
        Map<String, EntityState> entities = new HashMap<>(TestArea.seed());
        entities.put("entity:00000000-0000-4000-8000-000000000009", post(2.0, 4.0));
        FakeContext ctx = attack(entities, new Position(2.0, 4.0), 0.0);
        assertThat(result(ctx).hit()).isEqualTo(PILLAR);
    }

    @Test
    void aTieGoesToTheLowerId() {
        // both circles meet the line y = 4 first at x = 5.4
        Map<String, EntityState> entities = new HashMap<>();
        entities.put("entity:b", post(5.0, 4.3));
        entities.put("entity:a", post(5.0, 3.7));
        FakeContext ctx = attack(entities, PILLAR_CENTRE, 0.0);
        assertThat(result(ctx).hit()).isEqualTo("entity:a");
        assertThat(result(ctx).point().x()).isCloseTo(5.4, within(1e-9));
    }

    @Test
    void theSourceIsNeverHit() {
        Map<String, EntityState> entities = new HashMap<>(TestArea.seed());
        entities.put(ORBITER, post(8.0, 4.0));
        FakeContext ctx = attack(entities, PILLAR_CENTRE, 0.0);
        assertThat(result(ctx).hit()).isEqualTo(PILLAR);
    }

    @Test
    void itCreatesTheProjectileFlyingToThePoint() {
        FakeContext ctx = attack(TestArea.seed(), PILLAR_CENTRE, 0.0);
        assertThat(ctx.triggered).hasSize(1);
        assertThat(ctx.triggered.get(0).effect()).isSameAs(Create.INSTANCE);
        Create.Params create = (Create.Params) ctx.triggered.get(0).params();
        assertThat(create.parent()).isEqualTo(FakeContext.AREA);
        assertThat(create.state().name()).isEqualTo("pebble");
        assertThat(create.state().position()).isEqualTo(FROM);
        assertThat(create.start().type()).isEqualTo("fly");
        Fly.Params fly = WorldJson.fromJson(create.start().params(), Fly.Params.class);
        assertThat(fly.from()).isEqualTo(FROM);
        assertThat(fly.to()).isEqualTo(result(ctx).point());
        assertThat(fly.hit()).isEqualTo(PILLAR);
        assertThat(fly.speed()).isEqualTo(6.0);
        assertThat(fly.damage()).isEqualTo(new DamageRoll(2.0, 0.5, 0.5, 4.0));
        assertThat(fly.decay()).isEqualTo(new Decay.Params(20.0));
    }

    @Test
    void aMissFliesWithNoHit() {
        FakeContext ctx = attack(TestArea.seed(), PILLAR_CENTRE, 1.5);
        Create.Params create = (Create.Params) ctx.triggered.get(0).params();
        assertThat(create.start().params().has("hit")).isFalse();
    }

    @Test
    void aThrowFromTheBoundaryOutwardsHasNoLine() {
        FakeContext ctx = new FakeContext(TestArea.ROOM, Map.of(), new ScriptedRandom().normal(0.0));
        LineAttack.Params p = new LineAttack.Params(ORBITER, new Position(0.0, 4.0), new Position(-1.0, 4.0), 0.0,
                TestArea.projectile());
        assertThatThrownBy(() -> LineAttack.INSTANCE.run(p, ctx)).hasMessageStartingWith("line-attack: no line");
    }
}
