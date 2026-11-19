package com.pessimisticit.scarcity.world.model.rules;

import static com.pessimisticit.scarcity.world.model.rules.TestArea.ORBITER;
import static com.pessimisticit.scarcity.world.model.rules.TestArea.PILLAR;
import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.EntityTemplate;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** damage, break, create, destroy, and the effects that fly, decay and throw set off. */
class EffectsTest {

    private static final String PEBBLE = "entity:00000000-0000-4000-8000-000000000010";

    private static FakeContext ctx(Map<String, Entity> entities, ScriptedRandom rng) {
        return new FakeContext(TestArea.ROOM, entities, rng);
    }

    private static Entity pebble() {
        return TestArea.state("""
                {"resourceKind":"entity","entityKind":"mob","prototype":"pebble","name":"pebble","position":{"x":5.5,"y":4.0},"speed":6.0,"actions":["fly","decay"]}""");
    }

    private static final Decay DECAY = new Decay(Destroy.INSTANCE);
    private static final Break BREAK = new Break(Destroy.INSTANCE, Create.INSTANCE, DECAY);
    private static final Fly FLY = new Fly(Damage.INSTANCE, BREAK);
    private static final LineAttack LINE_ATTACK = new LineAttack(Create.INSTANCE, Damage.INSTANCE, FLY);
    private static final Throw THROW = new Throw(LINE_ATTACK);

    private static Break.Params breakPebble(double p) {
        return new Break.Params(PEBBLE, p, new EntityTemplate("rubble"),
                new Decay.Params(20.0));
    }

    @Test
    void damageDrawsTruncatedAndLowersHp() {
        // gaussian 1.0 -> 2.0 + 0.5 * 1.0 = 2.5
        FakeContext ctx = ctx(TestArea.seed(), new ScriptedRandom().normal(1.0));
        Damage.INSTANCE.run(new Damage.Params(PILLAR, 2.0, 0.5, 0.5, 4.0), ctx);
        assertThat(ctx.entities.get(PILLAR).hp()).isEqualTo(97.5);
        assertThat(ctx.result).isEqualTo(new Damage.Result(100.0, 97.5));
        assertThat(WorldJson.toTree(ctx.draws.get(0).draw())).isEqualTo(TestArea.tree("""
                {"dist":"gaussian","mean":2.0,"sd":0.5,"truncate":{"min":0.5,"max":4.0},"value":2.5}"""));
    }

    @Test
    void damageNeverTakesHpBelowZero() {
        FakeContext ctx = ctx(Map.of(PILLAR, TestArea.pillar().withHp(1.0)), new ScriptedRandom().normal(3.0));
        Damage.INSTANCE.run(new Damage.Params(PILLAR, 2.0, 0.5, 0.5, 4.0), ctx);
        assertThat(ctx.result).isEqualTo(new Damage.Result(1.0, 0.0));
    }

    @Test
    void damageSkipsATargetWithoutHpOrAGoneOneWithoutADraw() {
        FakeContext ctx = ctx(TestArea.seed(), new ScriptedRandom());
        Damage.INSTANCE.run(new Damage.Params(ORBITER, 2.0, 0.5, 0.5, 4.0), ctx);
        assertThat(ctx.result).isEqualTo(new Damage.Skipped("no hp"));
        Damage.INSTANCE.run(new Damage.Params("entity:gone", 2.0, 0.5, 0.5, 4.0), ctx);
        assertThat(ctx.result).isEqualTo(new Damage.Skipped("no hp"));
        assertThat(ctx.draws).isEmpty();
    }

    @Test
    void aBreakDestroysAndCreatesDecayingRubbleWhereItWas() {
        FakeContext ctx = ctx(Map.of(PEBBLE, pebble()), new ScriptedRandom().uniform(0.1));
        BREAK.run(breakPebble(0.5), ctx);
        assertThat(ctx.draws).containsExactly(new FakeContext.Drawn("break", new Draw.Bernoulli(0.5, 0.1, true)));
        assertThat(ctx.triggered).extracting(EffectCall::type).containsExactly("destroy", "create");
        assertThat(ctx.triggered.get(0).params()).isEqualTo(new Destroy.Params(PEBBLE));
        Create.Params create = (Create.Params) ctx.triggered.get(1).params();
        assertThat(create.entity().name()).isEqualTo("rubble");
        assertThat(create.entity().position()).isEqualTo(new Position(5.5, 4.0));
        assertThat(create.actionSpec()).isEqualTo(new ActionSpec("decay", TestArea.object("{\"duration\":20.0}")));
        assertThat(ctx.started).isEmpty();
    }

    @Test
    void noBreakMakesTheTargetDecay() {
        FakeContext ctx = ctx(Map.of(PEBBLE, pebble()), new ScriptedRandom().uniform(0.9));
        BREAK.run(breakPebble(0.5), ctx);
        assertThat(ctx.draws.get(0).draw()).isEqualTo(new Draw.Bernoulli(0.5, 0.9, false));
        assertThat(ctx.triggered).isEmpty();
        assertThat(ctx.started).containsExactly(new FakeContext.Started(PEBBLE,
                new ActionSpec("decay", TestArea.object("{\"duration\":20.0}"))));
    }

    @Test
    void breakingSomethingGoneDoesNothing() {
        FakeContext ctx = ctx(Map.of(), new ScriptedRandom());
        BREAK.run(breakPebble(0.5), ctx);
        assertThat(ctx.draws).isEmpty();
        assertThat(ctx.triggered).isEmpty();
        assertThat(ctx.started).isEmpty();
    }

    @Test
    void createMakesTheEntityAndStartsItsAction() {
        FakeContext ctx = ctx(Map.of(), new ScriptedRandom());
        ActionSpec decay = DECAY.spec(new Decay.Params(5.0));
        Create.INSTANCE.run(new Create.Params(FakeContext.AREA, pebble(), decay), ctx);
        assertThat(ctx.created).containsExactly("entity:created-1");
        assertThat(ctx.entities.get("entity:created-1")).isEqualTo(pebble());
        assertThat(ctx.started).containsExactly(new FakeContext.Started("entity:created-1", decay));

        Create.INSTANCE.run(new Create.Params(FakeContext.AREA, pebble(), null), ctx);
        assertThat(ctx.started).hasSize(1);
    }

    @Test
    void destroyDestroys() {
        FakeContext ctx = ctx(Map.of(PEBBLE, pebble()), new ScriptedRandom());
        Destroy.INSTANCE.run(new Destroy.Params(PEBBLE), ctx);
        assertThat(ctx.destroyed).containsExactly(PEBBLE);
        assertThat(ctx.exists(PEBBLE)).isFalse();
    }

    @Test
    void aFlightLandsThenDamagesTheHitAndBreaks() {
        Fly.Params p = new Fly.Params(new Position(8.0, 4.0), new Position(5.5, 4.0), 6.0, PILLAR,
                new DamageRoll(2.0, 0.5, 0.5, 4.0), 0.5, breakPebble(0.5).entityTemplate(), new Decay.Params(20.0));
        Actor self = TestArea.actor(PEBBLE, pebble().withPosition(new Position(8.0, 4.0)), Map.of());
        assertThat(FLY.duration(p, self.entity())).isEqualTo(2.5 / 6.0);
        Applied landed = FLY.apply(p, self);
        assertThat(landed.entity().position()).isEqualTo(new Position(5.5, 4.0));
        List<EffectCall<?>> calls = FLY.onEnd(p, self);
        assertThat(calls).extracting(EffectCall::type).containsExactly("damage", "break");
        assertThat(calls.get(0).params()).isEqualTo(new Damage.Params(PILLAR, 2.0, 0.5, 0.5, 4.0));
        assertThat(calls.get(1).params()).isEqualTo(breakPebble(0.5));
    }

    @Test
    void aFlyWiredToAFakeDamageTriggersTheFake() {
        Effect<Damage.Params> fakeDamage = new Effect<>() {
            @Override
            public String name() {
                return "fake-damage";
            }

            @Override
            public Class<Damage.Params> params() {
                return Damage.Params.class;
            }

            @Override
            public void run(Damage.Params params, EffectContext ctx) {
            }
        };
        Fly fly = new Fly(fakeDamage, BREAK);
        Fly.Params p = new Fly.Params(new Position(8.0, 4.0), new Position(5.5, 4.0), 6.0, PILLAR,
                new DamageRoll(2.0, 0.5, 0.5, 4.0), 0.5, breakPebble(0.5).entityTemplate(), new Decay.Params(20.0));
        Actor self = TestArea.actor(PEBBLE, pebble().withPosition(new Position(8.0, 4.0)), Map.of());
        assertThat(fly.onEnd(p, self)).extracting(EffectCall::type).containsExactly("fake-damage", "break");
    }

    @Test
    void aFlightThatHitNothingOnlyBreaks() {
        Fly.Params p = new Fly.Params(new Position(8.0, 4.0), new Position(0.0, 4.0), 6.0, null,
                new DamageRoll(2.0, 0.5, 0.5, 4.0), 0.5, breakPebble(0.5).entityTemplate(), new Decay.Params(20.0));
        Actor self = TestArea.actor(PEBBLE, pebble(), Map.of());
        assertThat(FLY.onEnd(p, self)).extracting(EffectCall::type).containsExactly("break");
    }

    @Test
    void aDecayEndsByDestroyingItself() {
        Actor self = TestArea.actor(PEBBLE, pebble(), Map.of());
        assertThat(DECAY.onEnd(new Decay.Params(20.0), self))
                .containsExactly(Destroy.INSTANCE.call(new Destroy.Params(PEBBLE)));
    }

    @Test
    void aThrowStartsALineAttackFromItsPositionAtThePoint() {
        Throw.Params p = new Throw.Params(new Position(5.0, 4.0), 0.2, 2.0, TestArea.projectile());
        Actor self = TestArea.actor(ORBITER, TestArea.orbiter(), Map.of(PILLAR, TestArea.pillar()));
        assertThat(THROW.duration(p, self.entity())).isEqualTo(2.0);
        assertThat(THROW.onStart(p, self)).containsExactly(LINE_ATTACK.call(
                new LineAttack.Params(ORBITER, new Position(8.0, 4.0), new Position(5.0, 4.0), 0.2, TestArea.projectile())));
        assertThat(THROW.apply(p, self).entity()).isEqualTo(self.entity());
    }
}
