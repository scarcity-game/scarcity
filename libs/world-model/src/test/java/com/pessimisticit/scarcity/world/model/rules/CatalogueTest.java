package com.pessimisticit.scarcity.world.model.rules;

import static com.pessimisticit.scarcity.world.model.rules.TestArea.ORBITER;
import static com.pessimisticit.scarcity.world.model.rules.TestArea.PILLAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.InvalidAction;
import com.pessimisticit.scarcity.world.model.Timing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The standard catalogue against docs/actions.md, and the start rules. */
class CatalogueTest {

    private static final Catalogue CATALOGUE = Catalogue.standard();
    private static final Pattern ROW = Pattern.compile("^\\| `([a-z-]+)` \\|");

    /** The first-column names of the table under {@code ### <heading>} in docs/actions.md. */
    private static SortedSet<String> docTable(String heading) throws IOException {
        List<String> lines = Files.readAllLines(Path.of("../../docs/actions.md"));
        SortedSet<String> names = new TreeSet<>();
        boolean in = false;
        for (String line : lines) {
            if (line.startsWith("#")) {
                in = line.equals("### " + heading);
                continue;
            }
            Matcher m = ROW.matcher(line);
            if (in && m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    private static Actor orbiter() {
        return TestArea.actor(ORBITER, TestArea.orbiter(), Map.of(PILLAR, TestArea.pillar()));
    }

    private static ActionSpec spec(String type, String params) {
        return new ActionSpec(type, TestArea.object(params));
    }

    private static String throwAt(String at) {
        return "{\"at\":" + at + ",\"spread\":0.2,\"recovery\":2.0,\"projectile\":" + TestArea.projectileJson() + "}";
    }

    @Test
    void theStandardCatalogueIsTheDocTables() throws IOException {
        assertThat(docTable("Action types")).containsExactly("decay", "fly", "step", "throw", "wait");
        assertThat(CATALOGUE.actionNames()).isEqualTo(docTable("Action types"));
        assertThat(CATALOGUE.effectNames()).isEqualTo(docTable("Effect types"));
    }

    @Test
    void everyTypeIsFoundByItsOwnName() {
        CATALOGUE.actionNames().forEach(n -> assertThat(CATALOGUE.action(n).orElseThrow().name()).isEqualTo(n));
        CATALOGUE.effectNames().forEach(n -> assertThat(CATALOGUE.effect(n).orElseThrow().name()).isEqualTo(n));
        assertThat(CATALOGUE.action("orbit-step")).isEmpty();
    }

    @Test
    void twoTypesWithOneNameAreAnError() {
        assertThatThrownBy(() -> Catalogue.of(List.of(Wait.INSTANCE, Wait.INSTANCE), List.of()))
                .hasMessage("two types named 'wait'");
    }

    @Test
    void aStartedThrowIsBoundAndBuildsItsActionInProgress() {
        BoundAction<?> bound = CATALOGUE.start(spec("throw", throwAt("{\"x\":5.0,\"y\":4.0}")), orbiter());
        assertThat(bound.action()).isSameAs(Throw.INSTANCE);
        ActionInProgress a = bound.inProgress(orbiter().state(), 10.0, 7L, null);
        assertThat(a.type()).isEqualTo("throw");
        assertThat(a.start()).isEqualTo(10.0);
        assertThat(a.end()).isEqualTo(12.0);
        assertThat(a.effect()).isEqualTo(Timing.START);
        assertThat(a.seq()).isEqualTo(7L);
        assertThat(a.params()).isEqualTo(TestArea.object(throwAt("{\"x\":5.0,\"y\":4.0}")));
        assertThat(bound.onStart(orbiter())).extracting(EffectCall::type).containsExactly("line-attack");
    }

    @Test
    void anUnknownTypeIsInvalid() {
        assertThatThrownBy(() -> CATALOGUE.start(spec("orbit-step", "{}"), orbiter()))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("type: unknown action 'orbit-step'");
    }

    @Test
    void aTypeNotInTheEntitysActionsIsInvalid() {
        assertThatThrownBy(() -> CATALOGUE.start(spec("decay", "{\"duration\":1.0}"), orbiter()))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("decay: not in the entity's actions [step, wait, throw]");
    }

    @Test
    void paramsThatDoNotDecodeNameTheField() {
        assertThatThrownBy(() -> CATALOGUE.start(spec("step", "{}"), orbiter()))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("step params at 'to': to: required");
        assertThatThrownBy(() -> CATALOGUE.start(spec("step", "{\"to\":{\"x\":1.0}}"), orbiter()))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("step params at 'to.y': y: required");
        assertThatThrownBy(() -> CATALOGUE.start(spec("wait", "{\"duration\":0.0}"), orbiter()))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("wait params: duration: must be > 0, got 0.0");
        assertThatThrownBy(() -> CATALOGUE.start(spec("wait", "{\"duration\":\"1.0\"}"), orbiter()))
                .isInstanceOf(InvalidAction.class)
                .hasMessageStartingWith("wait params at 'duration': ");
    }

    @Test
    void anUnknownParamIsInvalid() {
        assertThatThrownBy(() -> CATALOGUE.start(spec("throw", throwAt("{\"x\":5.0,\"y\":4.0}")
                .replace("\"spread\"", "\"target\":\"" + PILLAR + "\",\"spread\"")), orbiter()))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("throw params at 'target': target: unknown key");
    }

    @Test
    void aBadProjectileTemplateIsInvalidBeforeTheThrowStarts() {
        String params = throwAt("{\"x\":5.0,\"y\":4.0}").replace("\"name\":\"pebble\",", "");
        assertThatThrownBy(() -> CATALOGUE.start(spec("throw", params), orbiter()))
                .isInstanceOf(InvalidAction.class)
                .hasMessageStartingWith("throw params at 'projectile': state: ")
                .hasMessageContaining("name: required");
    }

    @Test
    void aValidateFailureNamesTheActionAndTheField() {
        assertThatThrownBy(() -> CATALOGUE.start(spec("throw", throwAt("{\"x\":8.0,\"y\":4.0}")), orbiter()))
                .isInstanceOf(InvalidAction.class)
                .hasMessageStartingWith("throw: at: must differ from the entity's position");
    }

    @Test
    void aDurationThatIsNotPositiveAndFiniteIsInvalid() {
        Catalogue cat = Catalogue.of(List.of(new Instant()), List.of());
        EntityState self = TestArea.orbiter();
        Actor actor = TestArea.actor(ORBITER, new EntityState(self.kind(), self.name(), self.position(), null, null,
                null, List.of("instant"), null, null, null), Map.of());
        assertThatThrownBy(() -> cat.start(spec("instant", "{}"), actor))
                .isInstanceOf(InvalidAction.class)
                .hasMessage("instant: duration: must be positive and finite, got 0.0");
    }

    @Test
    void resumeDecodesWithoutTheStartRules() {
        ActionInProgress decaying = new ActionInProgress("decay", TestArea.object("{\"duration\":20.0}"),
                10.0, 30.0, Timing.END, null, null);
        BoundAction<?> bound = CATALOGUE.resume(decaying);
        assertThat(bound.params()).isEqualTo(new Decay.Params(20.0));
        assertThat(bound.onEnd(orbiter())).containsExactly(Destroy.INSTANCE.call(new Destroy.Params(ORBITER)));
        ActionInProgress broken = new ActionInProgress("decay", TestArea.object("{}"), 10.0, 30.0, Timing.END, null, null);
        assertThatThrownBy(() -> CATALOGUE.resume(broken)).isInstanceOf(InvalidAction.class)
                .hasMessage("decay params at 'duration': duration: required");
    }

    /** An action that takes no time, which no start may have. */
    private static final class Instant implements Action<Instant.Params> {
        record Params() {
        }

        @Override
        public String name() {
            return "instant";
        }

        @Override
        public Class<Params> params() {
            return Params.class;
        }

        @Override
        public Timing timing() {
            return Timing.NONE;
        }

        @Override
        public double duration(Params params, EntityState self) {
            return 0.0;
        }
    }
}
