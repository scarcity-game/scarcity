package com.pessimisticit.scarcity.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;

/**
 * The pebble throw (docs/actions.md "Effect types" and "Action types", docs/log.md): one whole throw on a
 * scripted generator, the branches (miss, no break), one write per tick and a long run.
 */
class PebbleThrowTest extends LogTestBase {

    private static final Offset<Double> EPS = Offset.offset(1e-9);

    /** Behaviour draw 0.9 picks the third choice (throw) of weights 2/1/1. */
    private static final double THROW = 0.9;

    private static String label(JsonNode e) {
        String kind = e.path("kind").asText();
        return switch (kind) {
            case "effect" -> "effect:" + e.path("effect").path("type").asText();
            case "action-start", "action-end", "action-cancelled" -> kind + ":" + e.path("action").path("type").asText();
            default -> kind;
        };
    }

    /** Ticks once a game time unit until the game time passes {@code until}. */
    private void run(Ticker t, int until) {
        for (int i = 0; i <= until; i++) {
            t.tick();
            now.addAndGet(1000);
        }
    }

    private JsonNode theThrowStart(List<JsonNode> es) {
        return es.stream().filter(e -> label(e).equals("action-start:throw")).findFirst().orElseThrow();
    }

    /** The entries of the throw's tree, in seq order, without the orbiter's own end of the recovery. */
    private List<JsonNode> tree(List<JsonNode> es, long root) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : es) {
            if (e.path("root").asLong() == root && !(label(e).equals("action-end:throw"))) {
                out.add(e);
            }
        }
        return out;
    }

    private static JsonNode change(JsonNode entry, String id) {
        for (JsonNode c : entry.path("changes")) {
            if (c.path("id").asText().equals(id)) {
                return c;
            }
        }
        throw new AssertionError("entry " + entry.path("seq") + " has no change for " + id);
    }

    private void assertGapless(List<JsonNode> es) {
        double prev = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < es.size(); i++) {
            assertThat(es.get(i).path("seq").asLong()).isEqualTo(i + 1);
            assertThat(es.get(i).path("time").asDouble()).isGreaterThanOrEqualTo(prev);
            prev = es.get(i).path("time").asDouble();
        }
    }

    private List<String> entityIds() {
        return store.get(WorldIds.AREA).orElseThrow().children();
    }

    @Test
    void aWholeThrowThatHitsAndBreaksIsOneTreeToTheEndOfTheRubbleDecay() throws Exception {
        // behaviour u = THROW; break u = 0.1 < p 0.5 (breaks); scatter 0.0; damage gaussian 1.0 -> 2.0 + 0.5 * 1.0.
        ThrowScript g = new ThrowScript().uniform(THROW, 0.1).normal(0.0, 1.0);
        Ticker t = ticker(g);
        t.tick();                       // game 0.0: the throw starts, the pebble is in the air
        now.addAndGet(1000);
        t.tick();                       // game 1.0: the pebble landed and broke
        assertThat(entityIds()).hasSize(3);
        assertThat(entityIds()).startsWith(WorldIds.PILLAR, WorldIds.ORBITER);
        String rubbleId = entityIds().get(2);
        Resource rubble = store.get(rubbleId).orElseThrow();
        assertThat(rubble.state().path("name").asText()).isEqualTo("rubble");
        assertThat(rubble.state().path("position").path("x").asDouble()).isEqualTo(5.5, EPS);
        assertThat(rubble.state().path("position").path("y").asDouble()).isEqualTo(4.0, EPS);
        assertThat(rubble.state().path("action").path("type").asText()).isEqualTo("decay");
        assertThat(rubble.state().path("action").path("end").asDouble()).isEqualTo(0.5 / 6.0 * 5.0 + 20.0, Offset.offset(0.1));
        assertThat(store.get(WorldIds.PILLAR).orElseThrow().state().path("hp").asDouble()).isEqualTo(97.5, EPS);

        now.addAndGet(1000);
        run(t, 22);                     // the rubble's decay ends at about 20.4
        List<JsonNode> es = entries();
        assertGapless(es);

        JsonNode start = theThrowStart(es);
        long root = start.path("seq").asLong();
        List<JsonNode> tree = tree(es, root);
        assertThat(tree.stream().map(PebbleThrowTest::label).toList()).containsExactly(
                "action-start:throw",
                "effect:line-attack",
                "effect:create",
                "action-start:fly",
                "action-end:fly",
                "effect:damage",
                "effect:break",
                "effect:destroy",
                "effect:create",
                "action-start:decay",
                "action-end:decay",
                "effect:destroy");
        // the orbiter's own end of the recovery is in the same tree, caused by the start
        JsonNode throwEnd = es.stream().filter(e -> label(e).equals("action-end:throw")).findFirst().orElseThrow();
        assertThat(throwEnd.path("cause").asLong()).isEqualTo(root);
        assertThat(throwEnd.path("root").asLong()).isEqualTo(root);
        assertThat(throwEnd.path("time").asDouble()).isEqualTo(2.0, EPS);

        // cause links: index of the entry that caused each one (null for the root)
        int[] causedBy = {-1, 0, 1, 2, 3, 4, 4, 6, 6, 8, 9, 10};
        for (int i = 0; i < tree.size(); i++) {
            JsonNode e = tree.get(i);
            assertThat(e.path("root").asLong()).as("root of %s", label(e)).isEqualTo(root);
            if (causedBy[i] < 0) {
                assertThat(e.path("cause").isNull()).isTrue();
            } else {
                assertThat(e.path("cause").asLong()).as("cause of %s", label(e)).isEqualTo(tree.get(causedBy[i]).path("seq").asLong());
            }
        }

        // draws: each one in the entry that made it, in the contract's format
        assertThat(tree.get(0).path("draws")).isEqualTo(JSON.readTree("""
                [{"for":"behaviour","dist":"categorical","weights":[2.0,1.0,1.0],"u":0.9,"value":2}]"""));
        assertThat(tree.get(1).path("draws")).isEqualTo(JSON.readTree("""
                [{"for":"scatter","dist":"gaussian","mean":0.0,"sd":0.2,"value":0.0}]"""));
        assertThat(tree.get(5).path("draws")).isEqualTo(JSON.readTree("""
                [{"for":"damage","dist":"gaussian","mean":2.0,"sd":0.5,"truncate":{"min":0.5,"max":4.0},"value":2.5}]"""));
        assertThat(tree.get(6).path("draws")).isEqualTo(JSON.readTree("""
                [{"for":"break","dist":"bernoulli","p":0.5,"u":0.1,"value":true}]"""));
        for (int i : new int[] {2, 3, 4, 7, 8, 9, 10, 11}) {
            assertThat(tree.get(i).path("draws")).as("draws of %s", label(tree.get(i))).isEmpty();
        }

        // results and changes
        JsonNode attack = tree.get(1);
        assertThat(attack.path("result").path("hit").asText()).isEqualTo(WorldIds.PILLAR);
        assertThat(attack.path("result").path("direction").asDouble()).isEqualTo(Math.PI, EPS);
        assertThat(attack.path("result").path("point").path("x").asDouble()).isEqualTo(5.5, EPS);
        assertThat(attack.path("effect").path("params").path("source").asText()).isEqualTo(WorldIds.ORBITER);
        JsonNode created = tree.get(2).path("changes").get(0);
        assertThat(created.path("op").asText()).isEqualTo("created");
        assertThat(created.path("parent").asText()).isEqualTo(WorldIds.AREA);
        assertThat(created.path("state").path("name").asText()).isEqualTo("pebble");
        String pebbleId = created.path("id").asText();
        assertThat(created.path("version").asLong()).isEqualTo(1);
        assertThat(tree.get(3).path("resource").asText()).isEqualTo(pebbleId);
        assertThat(tree.get(4).path("resource").asText()).isEqualTo(pebbleId);
        assertThat(tree.get(3).path("action").path("seq").asLong()).isEqualTo(tree.get(3).path("seq").asLong());
        assertThat(tree.get(5).path("result").path("hpBefore").asDouble()).isEqualTo(100.0, EPS);
        assertThat(tree.get(5).path("result").path("hpAfter").asDouble()).isEqualTo(97.5, EPS);
        assertThat(change(tree.get(5), WorldIds.PILLAR).path("state").path("hp").asDouble()).isEqualTo(97.5, EPS);
        assertThat(change(tree.get(5), WorldIds.PILLAR).path("version").asLong()).isEqualTo(version(WorldIds.PILLAR));
        JsonNode destroyed = tree.get(7).path("changes").get(0);
        assertThat(destroyed.path("op").asText()).isEqualTo("destroyed");
        assertThat(destroyed.path("id").asText()).isEqualTo(pebbleId);
        JsonNode rubbleChange = tree.get(8).path("changes").get(0);
        assertThat(rubbleChange.path("op").asText()).isEqualTo("created");
        assertThat(rubbleChange.path("id").asText()).isEqualTo(rubbleId);
        assertThat(rubbleChange.path("parent").asText()).isEqualTo(WorldIds.AREA);
        assertThat(tree.get(9).path("resource").asText()).isEqualTo(rubbleId);
        assertThat(tree.get(11).path("changes").get(0).path("op").asText()).isEqualTo("destroyed");
        assertThat(tree.get(11).path("changes").get(0).path("id").asText()).isEqualTo(rubbleId);

        // the end: only the orbiter and the pillar are left, the pillar lost the drawn damage
        assertThat(entityIds()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER);
        assertThat(store.get(WorldIds.PILLAR).orElseThrow().state().path("hp").asDouble()).isEqualTo(97.5, EPS);
        assertThat(store.get(pebbleId)).isEmpty();
        assertThat(store.get(rubbleId)).isEmpty();
    }

    @Test
    void aPebbleThatDoesNotBreakDecaysOnItsOwn() throws Exception {
        // break u = 0.9 >= p: no break
        ThrowScript g = new ThrowScript().uniform(THROW, 0.9).normal(0.0, 0.0);
        Ticker t = ticker(g);
        run(t, 24);
        List<JsonNode> es = entries();
        assertGapless(es);
        long root = theThrowStart(es).path("seq").asLong();
        List<JsonNode> tree = tree(es, root);
        assertThat(tree.stream().map(PebbleThrowTest::label).toList()).containsExactly(
                "action-start:throw", "effect:line-attack", "effect:create", "action-start:fly", "action-end:fly",
                "effect:damage", "effect:break", "action-start:decay", "action-end:decay", "effect:destroy");
        JsonNode brk = tree.get(6);
        assertThat(brk.path("draws")).isEqualTo(JSON.readTree("""
                [{"for":"break","dist":"bernoulli","p":0.5,"u":0.9,"value":false}]"""));
        assertThat(brk.path("changes")).isEmpty();
        JsonNode decayStart = tree.get(7);
        assertThat(decayStart.path("cause").asLong()).isEqualTo(brk.path("seq").asLong());
        assertThat(decayStart.path("resource").asText()).isEqualTo(tree.get(2).path("changes").get(0).path("id").asText());
        assertThat(decayStart.path("action").path("end").asDouble() - decayStart.path("time").asDouble()).isEqualTo(20.0, EPS);
        assertThat(tree.get(9).path("changes").get(0).path("op").asText()).isEqualTo("destroyed");
        assertThat(es.stream().filter(e -> label(e).equals("effect:create")).count()).isEqualTo(1);
        assertThat(entityIds()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER);
        // damage 2.0 (gaussian 0.0)
        assertThat(store.get(WorldIds.PILLAR).orElseThrow().state().path("hp").asDouble()).isEqualTo(98.0, EPS);
    }

    @Test
    void aMissFliesToTheWallAndDoesNoDamage() throws Exception {
        // scatter gaussian 1.5 * sd 0.2 = 0.3 rad off: the line passes 0.89 from the pillar's centre (radius 0.5).
        ThrowScript g = new ThrowScript().uniform(THROW, 0.1).normal(1.5);
        Ticker t = ticker(g);
        run(t, 24);
        List<JsonNode> es = entries();
        assertGapless(es);
        long root = theThrowStart(es).path("seq").asLong();
        List<JsonNode> tree = tree(es, root);
        assertThat(tree.stream().map(PebbleThrowTest::label).toList()).containsExactly(
                "action-start:throw", "effect:line-attack", "effect:create", "action-start:fly", "action-end:fly",
                "effect:break", "effect:destroy", "effect:create", "action-start:decay", "action-end:decay",
                "effect:destroy");
        JsonNode result = tree.get(1).path("result");
        assertThat(result.path("hit").isNull()).isTrue();
        assertThat(result.path("direction").asDouble()).isEqualTo(Math.PI + 0.3, EPS);
        assertThat(result.path("point").path("x").asDouble()).isEqualTo(0.0, EPS);
        assertThat(result.path("point").path("y").asDouble()).isEqualTo(4.0 - 8.0 * Math.tan(0.3), EPS);
        JsonNode scatter = tree.get(1).path("draws").get(0);
        assertThat(tree.get(1).path("draws")).hasSize(1);
        assertThat(scatter.path("for").asText()).isEqualTo("scatter");
        assertThat(scatter.path("dist").asText()).isEqualTo("gaussian");
        assertThat(scatter.path("sd").asDouble()).isEqualTo(0.2, EPS);
        assertThat(scatter.path("value").asDouble()).isEqualTo(0.3, EPS);
        assertThat(store.get(WorldIds.PILLAR).orElseThrow().state().path("hp").asDouble()).isEqualTo(100.0, EPS);
        assertThat(es.stream().noneMatch(e -> label(e).equals("effect:damage"))).isTrue();
        // the rubble was created where the pebble landed
        assertThat(tree.get(7).path("changes").get(0).path("state").path("position").path("x").asDouble()).isEqualTo(0.0, EPS);
        assertThat(entityIds()).containsExactly(WorldIds.PILLAR, WorldIds.ORBITER);
    }

    @Test
    void aTickWithABreakIsOneAreaVersionAndOneAreaChangeWithChildren() throws Exception {
        ObjectNode orbiter = store.get(WorldIds.ORBITER).orElseThrow().state().deepCopy();
        orbiter.remove("behaviour");
        store.updateState(WorldIds.ORBITER, orbiter);
        List<ChangeBatch> published = new ArrayList<>();
        Ticker t = ticker(new ThrowScript(), batch -> {
            published.add(batch);
            publisher.publish(batch);
        });
        t.tick();       // first tick: anchors the clock only
        // a pebble about to land on the pillar, certain to break (p 1.0)
        ObjectNode pebble = (ObjectNode) JSON.readTree("""
                {"kind":"mobile","name":"pebble","speed":6.0,"actions":["fly","decay"],"position":{"x":5.5,"y":4.0},
                 "action":{"type":"fly","params":{"from":{"x":8.0,"y":4.0},"to":{"x":5.5,"y":4.0},"speed":6.0,
                   "hit":"%s","damage":{"mean":2.0,"sd":0.5,"min":0.5,"max":4.0},"p":1.0,
                   "debris":{"kind":"fixed","name":"rubble","actions":["decay"]},"decay":{"duration":20.0}},
                   "start":0.0,"end":0.4,"effect":"end"}}""".formatted(WorldIds.PILLAR));
        Resource p = Resource.newResource(ResourceType.ENTITY, pebble);
        store.create(p, WorldIds.AREA);
        long areaBefore = version(WorldIds.AREA);
        long pillarBefore = version(WorldIds.PILLAR);
        published.clear();

        now.addAndGet(1000);
        t.tick();

        assertThat(version(WorldIds.AREA)).isEqualTo(areaBefore + 1);
        assertThat(version(WorldIds.PILLAR)).isEqualTo(pillarBefore + 1);
        assertThat(store.get(p.id())).isEmpty();
        assertThat(published).hasSize(1);
        List<ChangeBatch.Change> areaChanges = published.get(0).changes().stream()
                .filter(c -> c.id().equals(WorldIds.AREA)).toList();
        assertThat(areaChanges).hasSize(1);
        assertThat(areaChanges.get(0).version()).isEqualTo(areaBefore + 1);
        List<String> children = store.get(WorldIds.AREA).orElseThrow().children();
        assertThat(children).hasSize(3).doesNotContain(p.id());
        assertThat(areaChanges.get(0).children()).containsExactlyElementsOf(children);
        assertThat(published.get(0).changes().stream().filter(c -> c.deleted()).map(ChangeBatch.Change::id).toList())
                .containsExactly(p.id());
        String rubbleId = children.get(2);
        ChangeBatch.Change rubble = published.get(0).changes().stream()
                .filter(c -> c.id().equals(rubbleId)).findFirst().orElseThrow();
        assertThat(rubble.version()).isEqualTo(1);
        assertThat(rubble.patch().path("name").asText()).isEqualTo("rubble");
    }

    @Test
    void aLongRunKeepsTheAreaBoundedAndNeverRollsBack() throws Exception {
        Ticker t = ticker(new SplittableRandom(2026));
        int maxEntities = 0;
        double minHp = 100.0;
        for (int i = 0; i < 2000; i++) {
            t.tick();                   // an exception here would be a rolled-back tick
            now.addAndGet(1000);
            maxEntities = Math.max(maxEntities, entityIds().size());
            minHp = Math.min(minHp, store.get(WorldIds.PILLAR).orElseThrow().state().path("hp").asDouble());
        }
        List<JsonNode> es = entries();
        assertGapless(es);
        long throwsMade = es.stream().filter(e -> label(e).equals("action-start:throw")).count();
        assertThat(throwsMade).isGreaterThan(100);
        assertThat(es.stream().filter(e -> label(e).equals("effect:create")).count()).isGreaterThanOrEqualTo(throwsMade);
        assertThat(maxEntities).isGreaterThan(3).isLessThan(60);
        assertThat(minHp).isGreaterThanOrEqualTo(0.0).isLessThan(100.0);
        assertThat(entityIds().size()).isLessThan(60);
        // every entity that is left is the orbiter, the pillar or a pebble or rubble in the middle of an action
        for (String id : entityIds()) {
            JsonNode s = store.get(id).orElseThrow().state();
            if (!id.equals(WorldIds.PILLAR) && !id.equals(WorldIds.ORBITER)) {
                assertThat(s.path("action").path("type").asText()).isIn("fly", "decay");
            }
        }
    }
}
