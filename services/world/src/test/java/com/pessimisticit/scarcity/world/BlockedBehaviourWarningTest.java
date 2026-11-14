package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** A behaviour that cannot start warns once, not on every tick (issue #85). */
class BlockedBehaviourWarningTest extends LogTestBase {

    static final String UNKNOWN = """
            {"kind":"repeat","action":{"type":"no-such-action","params":{}}}
            """;
    static final String ZERO_WAIT = """
            {"kind":"repeat","action":{"type":"wait","params":{"duration":0.0}}}
            """;

    private final Logger tickerLog = (Logger) LoggerFactory.getLogger(Ticker.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capture() {
        appender.start();
        tickerLog.addAppender(appender);
    }

    @AfterEach
    void release() {
        tickerLog.detachAppender(appender);
        appender.stop();
    }

    private List<String> warnings(String id) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(id + ":"))
                .toList();
    }

    private void ticks(Ticker t, int n) {
        for (int i = 0; i < n; i++) {
            now.addAndGet(200);
            t.tick();
        }
    }

    /** Replaces the orbiter's behaviour and drops any action in progress. */
    private void setIdleOrbiter(String behaviourJson) throws Exception {
        ObjectNode state = store.get(WorldIds.ORBITER).orElseThrow().state().deepCopy();
        state.set("behaviour", JSON.readTree(behaviourJson));
        state.remove("action");
        store.updateState(WorldIds.ORBITER, state);
    }

    @Test
    void warnsOnceUntilTheEntityStartsAnAction() throws Exception {
        setIdleOrbiter(UNKNOWN);
        Ticker t = ticker(new SplittableRandom(1));
        long versionBefore = version(WorldIds.ORBITER);
        int entriesBefore = entries().size();

        ticks(t, 10);

        assertThat(warnings(WorldIds.ORBITER)).hasSize(1);
        assertThat(warnings(WorldIds.ORBITER).get(0))
                .isEqualTo(WorldIds.ORBITER + ": action no-such-action not startable (known false, listed false)");
        // Game behaviour unchanged: the blocked entity is not written, logged or published.
        assertThat(version(WorldIds.ORBITER)).isEqualTo(versionBefore);
        assertThat(entries().subList(entriesBefore, entries().size()))
                .noneMatch(e -> e.path("resource").asText().equals(WorldIds.ORBITER));

        // Fixed: it starts an action, so the next failure warns again.
        setIdleOrbiter(REPEAT_ORBIT);
        ticks(t, 1);
        assertThat(t.warnedIds()).doesNotContain(WorldIds.ORBITER);

        setIdleOrbiter(UNKNOWN);
        ticks(t, 10);
        assertThat(warnings(WorldIds.ORBITER)).hasSize(2);
    }

    @Test
    void warnsAgainWhenTheReasonChanges() throws Exception {
        setIdleOrbiter(UNKNOWN);
        Ticker t = ticker(new SplittableRandom(1));
        ticks(t, 3);

        setIdleOrbiter(ZERO_WAIT);
        ticks(t, 3);

        assertThat(warnings(WorldIds.ORBITER)).containsExactly(
                WorldIds.ORBITER + ": action no-such-action not startable (known false, listed false)",
                WorldIds.ORBITER + ": action wait has duration 0.0; not started");
    }

    @Test
    void aDestroyedEntityIsForgotten() throws Exception {
        ObjectNode s = (ObjectNode) JSON.readTree("""
                {"kind":"mobile","name":"stuck","actions":["wait"],
                 "behaviour":{"kind":"repeat","action":{"type":"no-such-action","params":{}}}}
                """);
        Resource stuck = Resource.newResource(ResourceType.ENTITY, s);
        store.create(stuck, WorldIds.AREA);
        Ticker t = ticker(new SplittableRandom(1));

        ticks(t, 2);
        assertThat(t.warnedIds()).contains(stuck.id());
        assertThat(warnings(stuck.id())).hasSize(1);

        store.applyChildren(WorldIds.AREA, List.of(), List.of(stuck.id()), null);
        ticks(t, 1);
        assertThat(t.warnedIds()).doesNotContain(stuck.id());
    }
}
