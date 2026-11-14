package com.pessimisticit.scarcity.world.engine;

import static com.pessimisticit.scarcity.world.engine.Room.AREA;
import static com.pessimisticit.scarcity.world.engine.Room.ORBITER;
import static com.pessimisticit.scarcity.world.engine.Room.PILLAR;
import static com.pessimisticit.scarcity.world.engine.Room.RAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.engine.LogEntry.Kind;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.Position;
import com.pessimisticit.scarcity.world.model.Timing;
import com.pessimisticit.scarcity.world.model.rules.Action;
import com.pessimisticit.scarcity.world.model.rules.Actor;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import com.pessimisticit.scarcity.world.model.rules.Create;
import com.pessimisticit.scarcity.world.model.rules.Damage;
import com.pessimisticit.scarcity.world.model.rules.DamageRoll;
import com.pessimisticit.scarcity.world.model.rules.Destroy;
import com.pessimisticit.scarcity.world.model.rules.Effect;
import com.pessimisticit.scarcity.world.model.rules.EffectCall;
import com.pessimisticit.scarcity.world.model.rules.EffectContext;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Create, destroy and cancel, and the cap, with test rules beside the standard ones. */
class EffectEngineTest {

    record Target(@JsonProperty(required = true) String target) {
    }

    /** {@code smite}: at its start, damages the target and then destroys it. */
    static final class Smite implements Action<Target> {
        static final Smite INSTANCE = new Smite();

        @Override
        public String name() {
            return "smite";
        }

        @Override
        public Class<Target> params() {
            return Target.class;
        }

        @Override
        public Timing timing() {
            return Timing.START;
        }

        @Override
        public double duration(Target params, EntityState self) {
            return 1.0;
        }

        @Override
        public List<EffectCall<?>> onStart(Target params, Actor self) {
            return List.of(
                    Damage.INSTANCE.call(Damage.Params.of(params.target(), new DamageRoll(1.0, 0.1, 0.0, 2.0))),
                    Destroy.INSTANCE.call(new Destroy.Params(params.target())));
        }
    }

    /** {@code strike}: at its start, damages the target. */
    static final class Strike implements Action<Target> {
        static final Strike INSTANCE = new Strike();

        @Override
        public String name() {
            return "strike";
        }

        @Override
        public Class<Target> params() {
            return Target.class;
        }

        @Override
        public Timing timing() {
            return Timing.START;
        }

        @Override
        public double duration(Target params, EntityState self) {
            return 1.0;
        }

        @Override
        public List<EffectCall<?>> onStart(Target params, Actor self) {
            return List.of(Damage.INSTANCE.call(Damage.Params.of(params.target(), new DamageRoll(1.0, 0.1, 0.0, 2.0))));
        }
    }

    /** {@code echo}: triggers itself, forever. */
    static final class Echo implements Effect<Target> {
        static final Echo INSTANCE = new Echo();

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public Class<Target> params() {
            return Target.class;
        }

        @Override
        public void run(Target params, EffectContext ctx) {
            ctx.trigger(call(params));
        }
    }

    /** {@code shout}: sets off an echo at its start. */
    static final class Shout implements Action<Target> {
        static final Shout INSTANCE = new Shout();

        @Override
        public String name() {
            return "shout";
        }

        @Override
        public Class<Target> params() {
            return Target.class;
        }

        @Override
        public Timing timing() {
            return Timing.START;
        }

        @Override
        public double duration(Target params, EntityState self) {
            return 1.0;
        }

        @Override
        public List<EffectCall<?>> onStart(Target params, Actor self) {
            return List.of(Echo.INSTANCE.call(params));
        }
    }

    /** {@code conjure}: creates a rat at (1, 1) that starts a wait of 0.5. */
    static final class Conjure implements Action<Target> {
        static final Conjure INSTANCE = new Conjure();

        @Override
        public String name() {
            return "conjure";
        }

        @Override
        public Class<Target> params() {
            return Target.class;
        }

        @Override
        public Timing timing() {
            return Timing.NONE;
        }

        @Override
        public double duration(Target params, EntityState self) {
            return 1.0;
        }

        @Override
        public List<EffectCall<?>> onStart(Target params, Actor self) {
            EntityState rat = Room.rat(null).withPosition(new Position(1, 1));
            return List.of(Create.INSTANCE.call(new Create.Params(params.target(), rat,
                    Wait.INSTANCE.spec(new Wait.Params(0.5)))));
        }
    }

    private static final Catalogue RULES = Catalogue.of(
            List.of(Wait.INSTANCE, Smite.INSTANCE, Strike.INSTANCE, Shout.INSTANCE, Conjure.INSTANCE),
            List.of(Destroy.INSTANCE, Damage.INSTANCE, Create.INSTANCE, Echo.INSTANCE));

    private static Store store() {
        EntityState wizard = Room.state("""
                {"kind":"mobile","name":"wizard","position":{"x":8.0,"y":4.0},
                 "actions":["wait","smite","strike","shout","conjure"],"controller":{"kind":"service","principal":"orbital"}}""");
        EntityState rat = Room.rat(Room.repeatWait(1.0)).withAction(new ActionInProgress("wait",
                Room.object("{\"duration\":1.0}"), 10.0, 11.0, Timing.NONE, null, null));
        return new Store(Room.area(10.0), RULES).put(PILLAR, Room.pillar()).put(ORBITER, wizard).put(RAT, rat);
    }

    private static ActionSpec at(String action, String target) {
        return new ActionSpec(action, Room.object("{\"target\":\"" + target + "\"}"));
    }

    @Test
    void anEffectChangesAnEntityInTheMiddleOfItsAction() {
        Store store = store();
        store.put(RAT, store.state(RAT).withHp(5.0));
        AdvanceResult r = store.advance(Room.intent(at("strike", RAT)), new ScriptedRandom().normal(0.0));

        LogEntry damage = r.log().get(2);
        assertThat(damage.effect().type()).isEqualTo("damage");
        assertThat(damage.changes()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(RAT);
            EntityState hit = (EntityState) c.state();
            assertThat(hit.hp()).isEqualTo(4.0);
            assertThat(hit.action().type()).isEqualTo("wait");
            assertThat(hit.action().end()).isEqualTo(11.0);
        });
        assertThat(r.log()).anyMatch(e -> e.kind() == Kind.ACTION_END && RAT.equals(e.resource()));
    }

    @Test
    void destroyingAnEntityCancelsItsActionWhichNeverEnds() {
        Store store = store();
        AdvanceResult r = store.advance(Room.intent(at("smite", RAT)), new ScriptedRandom().normal(0.0));

        assertThat(r.log()).extracting(LogEntry::kind).containsExactly(Kind.INTENT, Kind.ACTION_START,
                Kind.EFFECT, Kind.EFFECT, Kind.ACTION_CANCELLED, Kind.ACTION_END);
        LogEntry destroy = r.log().get(3);
        LogEntry cancelled = r.log().get(4);
        assertThat(cancelled.cause()).isEqualTo(destroy.seq());
        assertThat(cancelled.resource()).isEqualTo(RAT);
        assertThat(cancelled.action().end()).isEqualTo(11.0);
        assertThat(r.log()).noneMatch(e -> e.kind() == Kind.ACTION_END && RAT.equals(e.resource()));
        assertThat(r.destroyed()).containsExactly(RAT);
        assertThat(store.has(RAT)).isFalse();
    }

    @Test
    void anEntityChangedThenDestroyedInOneAdvanceLogsNoVersion() {
        Store store = store();
        AdvanceResult r = store.advance(Room.intent(at("smite", PILLAR)), new ScriptedRandom().normal(0.0));

        LogEntry damage = r.log().get(2);
        assertThat(damage.effect().type()).isEqualTo("damage");
        assertThat(damage.changes()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(PILLAR);
            assertThat(c.version()).isNull();
            assertThat(((EntityState) c.state()).hp()).isEqualTo(99.0);
        });
        assertThat(r.log().get(3).changes()).containsExactly(LogChange.destroyed(PILLAR));
        assertThat(r.write(PILLAR)).contains(new Write(PILLAR, Write.Op.DESTROYED, null, null));
        assertThat(r.log()).noneMatch(e -> e.kind() == Kind.ACTION_CANCELLED);
    }

    @Test
    void aCreatedEntityStartsItsActionAndIsWrittenOnceAsCreated() {
        Store store = store();
        AdvanceResult r = store.advance(Room.intent(at("conjure", AREA)), new ScriptedRandom());

        String created = r.created().getFirst();
        LogEntry create = r.log().get(2);
        assertThat(create.changes()).singleElement().satisfies(c -> {
            assertThat(c.op()).isEqualTo(LogChange.Op.CREATED);
            assertThat(c.version()).isEqualTo(1L);
            assertThat(c.parent()).isEqualTo(AREA);
        });
        LogEntry start = r.log().get(3);
        assertThat(start.resource()).isEqualTo(created);
        assertThat(start.cause()).isEqualTo(create.seq());
        LogEntry end = r.log().stream().filter(e -> e.kind() == Kind.ACTION_END && created.equals(e.resource()))
                .findFirst().orElseThrow();
        assertThat(end.time()).isEqualTo(10.5);
        assertThat(end.root()).isEqualTo(r.log().getFirst().seq());
        assertThat(r.write(created)).hasValueSatisfying(w -> {
            assertThat(w.op()).isEqualTo(Write.Op.CREATED);
            assertThat(w.version()).isEqualTo(1L);
            assertThat(w.state().idle()).isTrue();
        });
        assertThat(created).startsWith("entity:");
    }

    @Test
    void aRuleThatNeverStopsFailsTheAdvance() {
        Store store = store();
        assertThatThrownBy(() -> store.advance(Room.intent(at("shout", RAT)), new ScriptedRandom()))
                .isInstanceOf(RunawayAdvance.class)
                .hasMessage("the advance of " + AREA + " processed more than 100000 events and effects;"
                        + " a rule never stops");
        assertThat(store.log()).isEmpty();
    }
}
