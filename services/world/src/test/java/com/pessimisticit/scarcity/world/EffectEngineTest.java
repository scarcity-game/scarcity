package com.pessimisticit.scarcity.world;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;

import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class EffectEngineTest extends LogTestBase {

    static List<EffectCall> calls(JsonNode arr) {
        List<EffectCall> out = new ArrayList<>();
        for (JsonNode c : arr) {
            out.add(new EffectCall(c.path("type").asText(), (ObjectNode) c.path("params").deepCopy()));
        }
        return out;
    }

    /** Action "fire": takes params.duration; sets off the effects listed in params.start / params.end. */
    static final class Fire implements ActionType {
        public String name() { return "fire"; }
        public Effect effect() { return Effect.NONE; }
        public double duration(JsonNode p, ObjectNode s) { return p.path("duration").asDouble(); }
        public void apply(JsonNode p, ObjectNode s) { }
        public List<EffectCall> onStart(JsonNode p, ObjectNode s) { return calls(p.path("start")); }
        public List<EffectCall> onEnd(JsonNode p, ObjectNode s) { return calls(p.path("end")); }
    }

    /** Effect "relay": triggers every effect in params.next ([{type, params}]) in order. */
    static final class Relay implements EffectType {
        public String name() { return "relay"; }
        public void run(JsonNode p, EffectContext ctx) {
            for (EffectCall c : calls(p.path("next"))) {
                ctx.trigger(c.type(), c.params());
            }
        }
    }

    /** Effect "poke": increments state.poked of params.target. */
    static final class Poke implements EffectType {
        public String name() { return "poke"; }
        public void run(JsonNode p, EffectContext ctx) {
            ObjectNode s = ctx.state(p.path("target").asText());
            s.put("poked", s.path("poked").asInt() + 1);
        }
    }

    /** Effect "spawn-and-destroy": creates params.state in the area and destroys it again. */
    static final class SpawnAndDestroy implements EffectType {
        public String name() { return "spawn-and-destroy"; }
        public void run(JsonNode p, EffectContext ctx) {
            String id = ctx.create(ctx.areaId(), (ObjectNode) p.path("state").deepCopy());
            ctx.destroy(id);
        }
    }

    /** Effect "loop": triggers itself forever. */
    static final class Loop implements EffectType {
        public String name() { return "loop"; }
        public void run(JsonNode p, EffectContext ctx) {
            ctx.trigger("loop", (ObjectNode) p.deepCopy());
        }
    }

    private List<ChangeBatch> published = new ArrayList<>();

    private Ticker fireTicker(java.util.random.RandomGenerator g, ChangePublisher pub) {
        return new Ticker(store, pub, tx, clock, tracer, g,
            List.of(new OrbitStep(), new Wait(), new Fire()), 5.0, areaLog,
            List.of(new CreateEffect(), new DestroyEffect(), new Relay(), new Poke(), new SpawnAndDestroy(), new Loop()));
    }

    private void arm(String endEffectsJson) throws Exception {
        ObjectNode state = store.get(WorldIds.ORBITER).orElseThrow().state().deepCopy();
        state.put("actions", JsonNodeFactory.instance.arrayNode().add("fire"));
        state.remove("behaviour");
        state.set("action", JSON.readTree(
            "{\"type\":\"fire\",\"params\":{\"duration\":1.0,\"start\":[],\"end\": " + endEffectsJson +
            "},\"start\":0.0,\"end\":1.0,\"effect\":\"none\"}"));
        store.updateState(WorldIds.ORBITER, state);
    }

    private ChangePublisher recording() {
        return batch -> {
            published.add(batch);
            publisher.publish(batch);
        };
    }

    private long runFirstTick(Ticker t) {
        t.tick();
        long lastSeq = entries().get(entries().size()-1).path("seq").asLong();
        return lastSeq;
    }

    private void runSecondTick(Ticker t, long firstSeq) {
        now.addAndGet(1500);
        published.clear();
        t.tick();
    }

    private String lastVictim;

    private String victim() throws Exception {
        // Create a victim entity with a wait action of duration 10.0 in progress, start 0.0, end 10.0
        ObjectNode vs = (ObjectNode) JSON.readTree("{\"kind\":\"mobile\",\"name\":\"victim\",\"actions\":[\"wait\"],\"action\":{\"type\":\"wait\",\"params\":{\"duration\":10.0},\"start\":0.0,\"end\":10.0,\"effect\":\"none\"}}");
        Resource v = Resource.newResource(ResourceType.ENTITY, vs);
        store.create(v, WorldIds.AREA);
        return v.id();
    }

    private List<JsonNode> runChain(Ticker t) throws Exception {
        String victim = victim();
        lastVictim = victim;
        arm("[{\"type\":\"relay\",\"params\":{\"next\":[{\"type\":\"relay\",\"params\":{\"next\":[{\"type\":\"destroy\",\"params\":{\"target\":\"" + victim + "\"}},{\"type\":\"create\",\"params\":{\"parent\":\"" + WorldIds.AREA + "\",\"state\":{\"kind\":\"mobile\",\"name\":\"pebble\",\"speed\":1.0,\"actions\":[\"wait\"]},\"start\":{\"type\":\"wait\",\"params\":{\"duration\":1.0}}}}]}}]}}]");
        long first = runFirstTick(t);
        runSecondTick(t, first);
        return entriesAfter(first);
    }

    @BeforeEach
    void clearPublished() {
        published.clear();
    }

    @org.junit.jupiter.api.Test
    void createWritesTheResourceTheParentAndTheNotification() throws Exception {
        arm("[{\"type\":\"create\",\"params\":{\"parent\":\"" + WorldIds.AREA + "\",\"state\":{\"kind\":\"mobile\",\"name\":\"pebble\",\"position\":{\"x\":1.0,\"y\":2.0}}}}]");

        Ticker t = fireTicker(new java.util.SplittableRandom(42), recording());
        long first = runFirstTick(t);

        // Capture versions before second tick
        long areaVersion = version(WorldIds.AREA);
        List<String> childrenBefore = store.get(WorldIds.AREA).orElseThrow().children();
        long orbiterVersion = version(WorldIds.ORBITER);

        runSecondTick(t, first);

        List<JsonNode> e = entriesAfter(first);

        String id = EffectRunner.newId(new java.util.SplittableRandom(42));

        // Assert resource exists with correct state and version
        assertThat(store.get(id)).isPresent();
        Resource r = store.get(id).orElseThrow();
        assertThat(r.version()).isEqualTo(1);
        assertThat(r.state().path("name").asText()).isEqualTo("pebble");

        // Assert area children include the new resource
        assertThat(store.get(WorldIds.AREA).orElseThrow().children()).containsExactlyElementsOf(
            java.util.stream.Stream.concat(childrenBefore.stream(), java.util.stream.Stream.of(id))
                .toList());

        // Assert area version was incremented
        assertThat(version(WorldIds.AREA)).isEqualTo(areaVersion + 1);

        // Assert orbiter version was incremented
        assertThat(version(WorldIds.ORBITER)).isEqualTo(orbiterVersion + 1);

        // Assert exactly one batch was published
        assertThat(published).hasSize(1);

        ChangeBatch batch = published.get(0);
        assertThat(batch.area()).isEqualTo(WorldIds.AREA);

        // Assert area change has correct version, children and patch
        ChangeBatch.Change areaChange = batch.changes().stream()
            .filter(c -> c.id().equals(WorldIds.AREA))
            .findFirst()
            .orElseThrow();
        assertThat(areaChange.version()).isEqualTo(version(WorldIds.AREA));
        assertThat(areaChange.children()).containsExactlyElementsOf(store.get(WorldIds.AREA).orElseThrow().children());
        assertThat(areaChange.patch()).isNotNull();

        // Assert change for new resource has correct properties
        ChangeBatch.Change resourceChange = batch.changes().stream()
            .filter(c -> c.id().equals(id))
            .findFirst()
            .orElseThrow();
        assertThat(resourceChange.version()).isEqualTo(1);
        assertThat(resourceChange.patch().path("name").asText()).isEqualTo("pebble");
        assertThat(resourceChange.children()).isNull();
        assertThat(resourceChange.deleted()).isFalse();

        // Assert log entries
        assertThat(kinds(e)).containsExactly("action-end", "effect");
        JsonNode effectEntry = e.get(1);
        assertThat(effectEntry.path("cause").asLong()).isEqualTo(e.get(0).path("seq").asLong());
        assertThat(effectEntry.path("root").asLong()).isEqualTo(e.get(0).path("seq").asLong());
        assertThat(effectEntry.path("resource").isNull()).isTrue();
        assertThat(effectEntry.path("effect").path("type").asText()).isEqualTo("create");
        assertThat(effectEntry.path("effect").path("params").path("parent").asText()).isEqualTo(WorldIds.AREA);
        assertThat(effectEntry.path("draws").isArray()).isTrue();
        assertThat(effectEntry.path("changes").isArray()).isTrue();
        assertThat(effectEntry.path("changes").get(0).path("op").asText()).isEqualTo("created");
        assertThat(effectEntry.path("changes").get(0).path("id").asText()).isEqualTo(id);
        assertThat(effectEntry.path("changes").get(0).path("version").asLong()).isEqualTo(1);
        assertThat(effectEntry.path("changes").get(0).path("parent").asText()).isEqualTo(WorldIds.AREA);
        assertThat(effectEntry.path("changes").get(0).path("type").asText()).isEqualTo("entity");
        assertThat(effectEntry.path("changes").get(0).path("state").path("name").asText()).isEqualTo("pebble");
        assertThat(effectEntry.has("result")).isFalse();
    }

    @org.junit.jupiter.api.Test
    void createCanStartAnAction() throws Exception {
        arm("[{\"type\":\"create\",\"params\":{\"parent\":\"" + WorldIds.AREA + "\",\"state\":{\"kind\":\"mobile\",\"name\":\"pebble\",\"speed\":1.0,\"actions\":[\"wait\"]},\"start\":{\"type\":\"wait\",\"params\":{\"duration\":2.0}}}}]");

        Ticker t = fireTicker(new java.util.SplittableRandom(42), recording());
        long first = runFirstTick(t);
        runSecondTick(t, first);

        List<JsonNode> e = entriesAfter(first);

        String id = EffectRunner.newId(new java.util.SplittableRandom(42));

        // Assert stored state has action properties
        Resource r = store.get(id).orElseThrow();
        ObjectNode state = r.state();
        assertThat(state.path("action").path("type").asText()).isEqualTo("wait");
        assertThat(state.path("action").path("start").asDouble()).isEqualTo(1.0);
        assertThat(state.path("action").path("end").asDouble()).isEqualTo(3.0);
        assertThat(state.path("action").path("seq").asLong()).isEqualTo(e.get(2).path("seq").asLong());

        // Assert log entries
        assertThat(kinds(e)).containsExactly("action-end", "effect", "action-start");
        JsonNode actionStartEntry = e.get(2);
        assertThat(actionStartEntry.path("cause").asLong()).isEqualTo(e.get(1).path("seq").asLong());
        assertThat(actionStartEntry.path("root").asLong()).isEqualTo(e.get(0).path("seq").asLong());
        assertThat(actionStartEntry.path("resource").asText()).isEqualTo(id);
        assertThat(actionStartEntry.path("time").asDouble()).isEqualTo(1.0);
        assertThat(actionStartEntry.path("changes").get(0).path("state").path("action").path("type").asText()).isEqualTo("wait");

        // Run the action to completion
        now.addAndGet(2500);
        t.tick();
        List<JsonNode> newEntries = entriesAfter(e.get(e.size() - 1).path("seq").asLong());

        // Assert action-end entry for resource id
        assertThat(kinds(newEntries)).containsExactly("action-end");
        JsonNode actionEndEntry = newEntries.get(0);
        assertThat(actionEndEntry.path("cause").asLong()).isEqualTo(e.get(2).path("seq").asLong());
        assertThat(actionEndEntry.path("root").asLong()).isEqualTo(e.get(0).path("seq").asLong());
        assertThat(actionEndEntry.path("resource").asText()).isEqualTo(id);
        assertThat(actionEndEntry.path("time").asDouble()).isEqualTo(3.0);

        // Assert stored state no longer has action
        r = store.get(id).orElseThrow();
        state = r.state();
        assertThat(state.has("action")).isFalse();
    }

    @org.junit.jupiter.api.Test
    void destroyingAResourceCancelsItsAction() throws Exception {
        // Create a victim resource with an action
        ObjectNode vs = (ObjectNode) JSON.readTree("{\"kind\":\"mobile\",\"name\":\"victim\",\"actions\":[\"wait\"],\"action\":{\"type\":\"wait\",\"params\":{\"duration\":10.0},\"start\":0.0,\"end\":10.0,\"effect\":\"none\"}}");
        Resource v = Resource.newResource(ResourceType.ENTITY, vs);
        store.create(v, WorldIds.AREA);
        String victim = v.id();

        arm("[{\"type\":\"destroy\",\"params\":{\"target\":\"" + victim + "\"}}]");

        Ticker t = fireTicker(new java.util.SplittableRandom(42), recording());
        long first = runFirstTick(t);
        runSecondTick(t, first);

        List<JsonNode> e = entriesAfter(first);

        // Assert resource is destroyed
        assertThat(store.get(victim)).isEmpty();

        // Assert area children do not contain victim
        assertThat(store.get(WorldIds.AREA).orElseThrow().children()).doesNotContain(victim);

        // Assert published batch has correct changes
        ChangeBatch batch = published.get(0);
        ChangeBatch.Change victimChange = batch.changes().stream()
            .filter(c -> c.id().equals(victim))
            .findFirst()
            .orElseThrow();
        assertThat(victimChange.deleted()).isTrue();
        assertThat(victimChange.version()).isEqualTo(0);
        assertThat(victimChange.patch()).isNull();

        ChangeBatch.Change areaChange = batch.changes().stream()
            .filter(c -> c.id().equals(WorldIds.AREA))
            .findFirst()
            .orElseThrow();
        assertThat(areaChange.children()).doesNotContain(victim);

        // Assert log entries
        assertThat(kinds(e)).containsExactly("action-end", "effect", "action-cancelled");
        JsonNode effectEntry = e.get(1);
        assertThat(effectEntry.path("effect").path("type").asText()).isEqualTo("destroy");
        assertThat(effectEntry.path("changes").isArray()).isTrue();
        assertThat(effectEntry.path("changes").get(0).path("op").asText()).isEqualTo("destroyed");
        assertThat(effectEntry.path("changes").get(0).path("id").asText()).isEqualTo(victim);
        // Should not have "version" or "state" keys in the change
        assertThat(effectEntry.path("changes").get(0).has("version")).isFalse();
        assertThat(effectEntry.path("changes").get(0).has("state")).isFalse();

        JsonNode actionCancelledEntry = e.get(2);
        assertThat(actionCancelledEntry.path("cause").asLong()).isEqualTo(e.get(1).path("seq").asLong());
        assertThat(actionCancelledEntry.path("root").asLong()).isEqualTo(e.get(0).path("seq").asLong());
        assertThat(actionCancelledEntry.path("resource").asText()).isEqualTo(victim);
        assertThat(actionCancelledEntry.path("action").path("type").asText()).isEqualTo("wait");

        // Run another tick to verify no more entries for victim
        now.addAndGet(20000);
        t.tick();
        List<JsonNode> newEntries = entriesAfter(e.get(e.size() - 1).path("seq").asLong());
        assertThat(newEntries.stream().anyMatch(entry -> entry.path("resource").asText().equals(victim))).isFalse();
    }

    @org.junit.jupiter.api.Test
    void aResourceCreatedAndDestroyedInOneTickIsNeverStored() throws Exception {
        arm("[{\"type\":\"spawn-and-destroy\",\"params\":{\"state\":{\"kind\":\"mobile\",\"name\":\"ghost\",\"actions\":[\"wait\"],\"action\":{\"type\":\"wait\",\"params\":{\"duration\":5.0},\"start\":1.0,\"end\":6.0,\"effect\":\"none\"}}}}]");

        Ticker t = fireTicker(new java.util.SplittableRandom(7), recording());
        long first = runFirstTick(t);

        // Capture versions and children before second tick
        long areaVersion = version(WorldIds.AREA);
        List<String> childrenBefore = store.get(WorldIds.AREA).orElseThrow().children();

        runSecondTick(t, first);

        List<JsonNode> e = entriesAfter(first);

        String id = EffectRunner.newId(new java.util.SplittableRandom(7));

        // Assert: store.get(id) is empty
        assertThat(store.get(id)).isEmpty();

        // Assert: version(WorldIds.AREA) equals areaVersion and the area's children are unchanged
        assertThat(version(WorldIds.AREA)).isEqualTo(areaVersion);
        assertThat(store.get(WorldIds.AREA).orElseThrow().children()).containsExactlyElementsOf(childrenBefore);

        // Assert: published batches contain no change with that id and no change for the area, and no change with deleted() true
        assertThat(published.stream().flatMap(b -> b.changes().stream()))
            .noneMatch(c -> c.id().equals(id));
        assertThat(published.stream().flatMap(b -> b.changes().stream()))
            .noneMatch(c -> c.id().equals(WorldIds.AREA) && c.deleted());

        // Assert: log kinds(e) equals List.of("action-end","effect","action-cancelled")
        assertThat(kinds(e)).containsExactly("action-end", "effect", "action-cancelled");

        // Assert: effect entry e.get(1) has 2 changes
        JsonNode effectEntry = e.get(1);
        assertThat(effectEntry.path("changes").isArray()).isTrue();
        assertThat(effectEntry.path("changes").size()).isEqualTo(2);

        // First op "created" with id `id` and a JSON-null version (`c.path("version").isNull()` true) and parent AREA
        JsonNode firstChange = effectEntry.path("changes").get(0);
        assertThat(firstChange.path("op").asText()).isEqualTo("created");
        assertThat(firstChange.path("id").asText()).isEqualTo(id);
        assertThat(firstChange.path("version").isNull()).isTrue();
        assertThat(firstChange.path("parent").asText()).isEqualTo(WorldIds.AREA);

        // Second op "destroyed" with id `id` and no "version" key
        JsonNode secondChange = effectEntry.path("changes").get(1);
        assertThat(secondChange.path("op").asText()).isEqualTo("destroyed");
        assertThat(secondChange.path("id").asText()).isEqualTo(id);
        assertThat(secondChange.has("version")).isFalse();

        // Assert: action-cancelled entry e.get(2) - cause == e.get(1).seq, resource == id, action.type "wait"
        JsonNode cancelledEntry = e.get(2);
        assertThat(cancelledEntry.path("cause").asLong()).isEqualTo(effectEntry.path("seq").asLong());
        assertThat(cancelledEntry.path("resource").asText()).isEqualTo(id);
        assertThat(cancelledEntry.path("action").path("type").asText()).isEqualTo("wait");
    }

    @org.junit.jupiter.api.Test
    void oneWritePerResourcePerTickHoldsForEffects() throws Exception {
        arm("[{\"type\":\"poke\",\"params\":{\"target\":\"" + WorldIds.PILLAR + "\"}},{\"type\":\"poke\",\"params\":{\"target\":\"" + WorldIds.PILLAR + "\"}}]");

        Ticker t = fireTicker(new java.util.SplittableRandom(42), recording());
        long first = runFirstTick(t);

        // Capture versions before second tick
        long pillarVersion = version(WorldIds.PILLAR);
        long orbiterVersion = version(WorldIds.ORBITER);

        runSecondTick(t, first);

        List<JsonNode> e = entriesAfter(first);

        // Assert: pillar version == pillarVersion + 1 and its state poked is 2
        assertThat(version(WorldIds.PILLAR)).isEqualTo(pillarVersion + 1);
        assertThat(store.get(WorldIds.PILLAR).orElseThrow().state().path("poked").asInt()).isEqualTo(2);

        // Assert: orbiter version == orbiterVersion + 1
        assertThat(version(WorldIds.ORBITER)).isEqualTo(orbiterVersion + 1);

        // Assert: published has one batch whose changes contain exactly one change for the pillar (version pillarVersion+1)
        // and exactly one for the orbiter
        assertThat(published).hasSize(1);
        ChangeBatch batch = published.get(0);
        assertThat(batch.changes()).hasSize(2);  // One for pillar, one for orbiter

        ChangeBatch.Change pillarChange = batch.changes().stream()
            .filter(c -> c.id().equals(WorldIds.PILLAR))
            .findFirst()
            .orElseThrow();
        assertThat(pillarChange.version()).isEqualTo(pillarVersion + 1);

        ChangeBatch.Change orbiterChange = batch.changes().stream()
            .filter(c -> c.id().equals(WorldIds.ORBITER))
            .findFirst()
            .orElseThrow();
        assertThat(orbiterChange.version()).isEqualTo(orbiterVersion + 1);

        // Assert: log kinds(e) equals List.of("action-end","effect","effect")
        assertThat(kinds(e)).containsExactly("action-end", "effect", "effect");

        // Both effect entries list the pillar in their changes with version pillarVersion+1 and ops "changed"
        JsonNode firstEffectEntry = e.get(1);
        JsonNode secondEffectEntry = e.get(2);

        assertThat(firstEffectEntry.path("changes").isArray()).isTrue();
        assertThat(secondEffectEntry.path("changes").isArray()).isTrue();

        // First effect entry has pillar change with version pillarVersion+1 and poked=1
        JsonNode firstPillarChange = firstEffectEntry.path("changes").get(0);
        assertThat(firstPillarChange.path("id").asText()).isEqualTo(WorldIds.PILLAR);
        assertThat(firstPillarChange.path("op").asText()).isEqualTo("changed");
        assertThat(firstPillarChange.path("version").asLong()).isEqualTo(pillarVersion + 1);
        assertThat(firstPillarChange.path("state").path("poked").asInt()).isEqualTo(1);

        // Second effect entry has pillar change with version pillarVersion+1 and poked=2
        JsonNode secondPillarChange = secondEffectEntry.path("changes").get(0);
        assertThat(secondPillarChange.path("id").asText()).isEqualTo(WorldIds.PILLAR);
        assertThat(secondPillarChange.path("op").asText()).isEqualTo("changed");
        assertThat(secondPillarChange.path("version").asLong()).isEqualTo(pillarVersion + 1);
        assertThat(secondPillarChange.path("state").path("poked").asInt()).isEqualTo(2);

        // Both have cause == e.get(0).seq and root == e.get(0).seq
        assertThat(firstEffectEntry.path("cause").asLong()).isEqualTo(e.get(0).path("seq").asLong());
        assertThat(firstEffectEntry.path("root").asLong()).isEqualTo(e.get(0).path("seq").asLong());
        assertThat(secondEffectEntry.path("cause").asLong()).isEqualTo(e.get(0).path("seq").asLong());
        assertThat(secondEffectEntry.path("root").asLong()).isEqualTo(e.get(0).path("seq").asLong());
    }

    @org.junit.jupiter.api.Test
    void aRunawayEffectChainFailsTheTickAndRollsBack() throws Exception {
        arm("[{\"type\":\"loop\",\"params\":{}}]");

        Ticker t = fireTicker(new java.util.SplittableRandom(42), recording());
        long first = runFirstTick(t);

        // Capture versions and entry count before second tick
        long orbiterVersion = version(WorldIds.ORBITER);
        long areaVersion = version(WorldIds.AREA);
        int entriesCount = entries().size();

        published.clear();  // Clear any published changes from the first tick

        now.addAndGet(1500);

        // Expect an IllegalStateException with message containing "more than 100000 events"
        assertThatThrownBy(t::tick).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("more than 100000 events");

        // Assert: orbiter version and area version unchanged
        assertThat(version(WorldIds.ORBITER)).isEqualTo(orbiterVersion);
        assertThat(version(WorldIds.AREA)).isEqualTo(areaVersion);

        // Assert: the orbiter state still has an action object
        assertThat(store.get(WorldIds.ORBITER).orElseThrow().state().path("action").isObject()).isTrue();

        // Assert: entries().size() unchanged
        assertThat(entries().size()).isEqualTo(entriesCount);

        // Assert: published is empty
        assertThat(published).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void aChainThreeEffectsDeepFormsTheRightCauseAndRootTree() throws Exception {
        Ticker t = fireTicker(new java.util.SplittableRandom(5), recording());
        List<JsonNode> e = runChain(t);

        // Expected entries of the second tick, in order (index: kind, cause index, notes):
        // 0 action-end (orbiter; its action has no seq, so cause is JSON null),
        // 1 effect relay (cause 0), 2 effect relay (cause 1), 3 effect destroy (cause 2), 4 action-cancelled (cause 3, resource victim),
        // 5 effect create (cause 2), 6 action-start (cause 5; resource = the pebble id)
        assertThat(kinds(e)).containsExactly("action-end", "effect", "effect", "effect", "action-cancelled", "effect", "action-start");

        // The seq of entry i equals the seq of entry 0 plus i (gapless)
        long firstSeq = e.get(0).path("seq").asLong();
        for (int i = 0; i < e.size(); i++) {
            assertThat(e.get(i).path("seq").asLong()).isEqualTo(firstSeq + i);
        }

        // For i >= 1 the cause equals the seq of entry (cause index above)
        assertThat(e.get(1).path("cause").asLong()).isEqualTo(e.get(0).path("seq").asLong());  // cause 0
        assertThat(e.get(2).path("cause").asLong()).isEqualTo(e.get(1).path("seq").asLong());  // cause 1
        assertThat(e.get(3).path("cause").asLong()).isEqualTo(e.get(2).path("seq").asLong());  // cause 2
        assertThat(e.get(4).path("cause").asLong()).isEqualTo(e.get(3).path("seq").asLong());  // cause 3
        assertThat(e.get(5).path("cause").asLong()).isEqualTo(e.get(2).path("seq").asLong());  // cause 2
        assertThat(e.get(6).path("cause").asLong()).isEqualTo(e.get(5).path("seq").asLong());  // cause 5

        // Entry 0's cause isNull
        assertThat(e.get(0).path("cause").isNull()).isTrue();

        // Every entry's root equals the seq of entry 0
        for (JsonNode entry : e) {
            assertThat(entry.path("root").asLong()).isEqualTo(firstSeq);
        }

        // The effect.type of entries 1,2,3,5 are relay, relay, destroy, create
        assertThat(e.get(1).path("effect").path("type").asText()).isEqualTo("relay");
        assertThat(e.get(2).path("effect").path("type").asText()).isEqualTo("relay");
        assertThat(e.get(3).path("effect").path("type").asText()).isEqualTo("destroy");
        assertThat(e.get(5).path("effect").path("type").asText()).isEqualTo("create");

        // All entries have the same time 1.0
        for (JsonNode entry : e) {
            assertThat(entry.path("time").asDouble()).isEqualTo(1.0);
        }

        // Whole log gapless: entries() seq values are 1, 2, 3, ... consecutive
        List<JsonNode> whole = entries();
        for (int i = 0; i < whole.size(); i++) {
            assertThat(whole.get(i).path("seq").asLong()).isEqualTo(i + 1L);
        }

        // Advance now and tick again
        now.addAndGet(2500);
        t.tick();
        List<JsonNode> newEntries = entriesAfter(e.get(e.size() - 1).path("seq").asLong());

        // The new entries contain an action-end for the pebble id whose cause equals the seq of entry 6
        // and whose root equals the seq of entry 0
        assertThat(newEntries.stream().anyMatch(entry ->
            "action-end".equals(entry.path("kind").asText()) &&
            EffectRunner.newId(new java.util.SplittableRandom(5)).equals(entry.path("resource").asText()) &&
            entry.path("cause").asLong() == e.get(6).path("seq").asLong() &&
            entry.path("root").asLong() == e.get(0).path("seq").asLong()
        )).isTrue();
    }

    @org.junit.jupiter.api.Test
    void theLogApiReturnsEffectEntriesInTheContractShape() throws Exception {
        Ticker t = fireTicker(new java.util.SplittableRandom(5), recording());
        List<JsonNode> e = runChain(t);

        // GET /api/world/log?area=<AREA>&kind=effect with header Authorization: Bearer <TestSupport.validToken()>
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/world/log?area=" + WorldIds.AREA + "&kind=effect"));
        b.header("Authorization", "Bearer " + TestSupport.validToken());
        HttpResponse<String> response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());

        // Assert status 200 and parse the body
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode apiResponse = JSON.readTree(response.body());

        // Assert entries has exactly 4 elements
        assertThat(apiResponse.path("entries").isArray()).isTrue();
        assertThat(apiResponse.path("entries").size()).isEqualTo(4);

        // Each entry should have:
        // kind "effect", an integer seq, time 1.0, cause number, root number, resource JSON null,
        // effect.type text and an effect.params object, draws array, changes array, and area equal to WorldIds.AREA
        for (JsonNode entry : apiResponse.path("entries")) {
            assertThat(entry.path("kind").asText()).isEqualTo("effect");
            assertThat(entry.path("seq").isInt()).isTrue();
            assertThat(entry.path("time").asDouble()).isEqualTo(1.0);
            assertThat(entry.path("cause").isNumber()).isTrue();
            assertThat(entry.path("root").isNumber()).isTrue();
            assertThat(entry.path("resource").isNull()).isTrue();
            assertThat(entry.path("effect").path("type").isTextual()).isTrue();
            assertThat(entry.path("effect").path("params").isObject()).isTrue();
            assertThat(entry.path("draws").isArray()).isTrue();
            assertThat(entry.path("changes").isArray()).isTrue();
            assertThat(entry.path("area").asText()).isEqualTo(WorldIds.AREA);
        }

        // The entry whose effect.type is "create" should have changes[0].op "created",
        // changes[0].parent AREA and changes[0].state.name "pebble"
        JsonNode createEntry = null;
        for (JsonNode entry : apiResponse.path("entries")) {
            if ("create".equals(entry.path("effect").path("type").asText())) {
                createEntry = entry;
                break;
            }
        }
        assertThat(createEntry).isNotNull();
        assertThat(createEntry.path("changes").get(0).path("op").asText()).isEqualTo("created");
        assertThat(createEntry.path("changes").get(0).path("parent").asText()).isEqualTo(WorldIds.AREA);
        assertThat(createEntry.path("changes").get(0).path("state").path("name").asText()).isEqualTo("pebble");

        // The entry with effect.type "destroy" should have changes[0].op "destroyed" and no "state" key
        JsonNode destroyEntry = null;
        for (JsonNode entry : apiResponse.path("entries")) {
            if ("destroy".equals(entry.path("effect").path("type").asText())) {
                destroyEntry = entry;
                break;
            }
        }
        assertThat(destroyEntry).isNotNull();
        assertThat(destroyEntry.path("changes").get(0).path("op").asText()).isEqualTo("destroyed");
        assertThat(destroyEntry.path("changes").get(0).has("state")).isFalse();

        // GET with kind=action-cancelled: one entry, its action.type is "wait" and resource is the victim id
        HttpRequest.Builder b2 = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/world/log?area=" + WorldIds.AREA + "&kind=action-cancelled"));
        b2.header("Authorization", "Bearer " + TestSupport.validToken());
        HttpResponse<String> response2 = http.send(b2.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response2.statusCode()).isEqualTo(200);
        JsonNode apiResponse2 = JSON.readTree(response2.body());

        assertThat(apiResponse2.path("entries").isArray()).isTrue();
        assertThat(apiResponse2.path("entries").size()).isEqualTo(1);
        JsonNode cancelledEntry = apiResponse2.path("entries").get(0);
        assertThat(cancelledEntry.path("action").path("type").asText()).isEqualTo("wait");
        String victimId = lastVictim;
        assertThat(cancelledEntry.path("resource").asText()).isEqualTo(victimId);

        // Assert first and last in the body are numbers
        assertThat(apiResponse.path("first").isNumber()).isTrue();
        assertThat(apiResponse.path("last").isNumber()).isTrue();
    }
}