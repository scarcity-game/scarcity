package com.pessimisticit.scarcity.world;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.Page;
import com.pessimisticit.scarcity.resourcemodel.Resource;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;
import com.pessimisticit.scarcity.resourcemodel.WriteResult;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * One tick of the test area: the generic action engine (contract: {@code docs/resources.md},
 * "Game time" and "Actions"). A tick is one transaction: every write and the one change batch
 * commit together (or not at all).
 *
 * <p>Events are action starts and ends. Every event at or before the current game time is
 * processed in order of time, ties by resource id. A chained action starts at the previous
 * action's {@code end}. Each resource is written once, with its final state.
 */
@Component
public class Ticker {

    private static final Logger log = LoggerFactory.getLogger(Ticker.class);

    /** Safety net against an action type with a (near) zero duration. */
    private static final int MAX_EVENTS_PER_TICK = 100_000;

    private final ResourceStore store;
    private final ChangePublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Tracer tracer;
    private final RandomGenerator generator;
    private final Map<String, ActionType> types = new HashMap<>();
    private final double maxLag;

    @Autowired
    public Ticker(ResourceStore store, ChangePublisher publisher, TransactionTemplate tx, Clock clock,
            Tracer tracer, RandomGenerator generator, List<ActionType> actionTypes,
            @Value("${scarcity.world.tick.max-lag:5.0}") double maxLag) {
        this.store = store;
        this.publisher = publisher;
        this.tx = tx;
        this.clock = clock;
        this.tracer = tracer;
        this.generator = generator;
        this.maxLag = maxLag;
        for (ActionType t : actionTypes) {
            types.put(t.name(), t);
        }
    }

    /** Runs one tick inside a {@code world.tick} span. Exceptions propagate to the caller. */
    public void tick() {
        Span span = tracer.nextSpan().name("world.tick").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            tx.executeWithoutResult(status -> doTick());
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void doTick() {
        Resource area = store.get(WorldIds.AREA).orElse(null);
        if (area == null) {
            log.debug("tick skipped: {} not seeded yet", WorldIds.AREA);
            return;
        }
        long nowMillis = clock.millis();
        ObjectNode areaState = area.state().deepCopy();
        boolean areaDirty = false;

        JsonNode clockNode = areaState.path("clock");
        double gameNow;
        if (!clockNode.isObject()) {
            writeClock(areaState, 0.0, nowMillis, 1.0);
            areaDirty = true;
            gameNow = 0.0;
        } else {
            gameNow = clockNode.path("gameTime").asDouble()
                    + (nowMillis - clockNode.path("wallTime").asLong()) / 1000.0 * clockNode.path("rate").asDouble();
        }

        Map<String, ObjectNode> entities = loadEntities();
        Set<String> dirty = new HashSet<>();
        Set<String> blocked = new HashSet<>();

        // Pull-back: the area was not being ticked; restart the clock at the earliest due event.
        double earliest = Double.POSITIVE_INFINITY;
        for (ObjectNode e : entities.values()) {
            JsonNode action = e.path("action");
            if (action.isObject() && action.path("end").asDouble() <= gameNow) {
                earliest = Math.min(earliest, action.path("end").asDouble());
            }
        }
        if (gameNow - earliest > maxLag) {
            double rate = areaState.path("clock").path("rate").asDouble();
            writeClock(areaState, earliest, nowMillis, rate);
            areaDirty = true;
            log.warn("area {} was {} time units behind; clock pulled back to {}", WorldIds.AREA,
                    gameNow - earliest, earliest);
            gameNow = earliest;
        }

        for (int n = 0;; n++) {
            String id = nextDue(entities, blocked, gameNow);
            if (id == null) {
                break;
            }
            if (n >= MAX_EVENTS_PER_TICK) {
                throw new IllegalStateException("more than " + MAX_EVENTS_PER_TICK + " events in one tick");
            }
            ObjectNode state = entities.get(id);
            JsonNode action = state.path("action");
            double startAt;
            if (action.isObject()) {
                double end = action.path("end").asDouble();
                if ("end".equals(action.path("effect").asText())) {
                    ActionType t = types.get(action.path("type").asText());
                    if (t == null) {
                        log.warn("{}: unknown action type {} ended without effect", id, action.path("type").asText());
                    } else {
                        t.apply(action.path("params"), state);
                    }
                }
                state.remove("action");
                dirty.add(id);
                startAt = end;
            } else {
                startAt = gameNow;
            }
            if (state.path("behaviour").isObject()) {
                if (start(id, state, startAt)) {
                    dirty.add(id);
                } else {
                    blocked.add(id);
                }
            }
        }

        if (!areaDirty && dirty.isEmpty()) {
            return;
        }
        List<ChangeBatch.Change> changes = new ArrayList<>();
        if (areaDirty) {
            WriteResult w = store.updateState(WorldIds.AREA, areaState);
            changes.add(new ChangeBatch.Change(w.id(), ResourceType.AREA, w.version(), areaState));
        }
        for (String id : dirty.stream().sorted().toList()) {
            WriteResult w = store.updateState(id, entities.get(id));
            changes.add(new ChangeBatch.Change(w.id(), ResourceType.ENTITY, w.version(), entities.get(id)));
        }
        publisher.publish(new ChangeBatch(WorldIds.AREA, changes, null));
    }

    private static void writeClock(ObjectNode areaState, double gameTime, long wallTime, double rate) {
        ObjectNode c = areaState.putObject("clock");
        c.put("gameTime", gameTime);
        c.put("wallTime", wallTime);
        c.put("rate", rate);
    }

    /** Entity states of the area (copies), ordered by id. */
    private Map<String, ObjectNode> loadEntities() {
        Map<String, ObjectNode> out = new TreeMap<>();
        for (int page = 0;; page++) {
            Page<Resource> p = store.children(WorldIds.AREA, ResourceType.ENTITY, page, ResourceStore.MAX_SIZE);
            for (Resource r : p.items()) {
                out.put(r.id(), r.state().deepCopy());
            }
            if (p.items().size() < ResourceStore.MAX_SIZE) {
                break;
            }
        }
        return out;
    }

    /** The entity whose event is due and earliest (ties: smallest id), or null. */
    private static String nextDue(Map<String, ObjectNode> entities, Set<String> blocked, double gameNow) {
        String best = null;
        double bestTime = 0;
        for (Map.Entry<String, ObjectNode> e : entities.entrySet()) { // TreeMap: ascending id
            if (blocked.contains(e.getKey())) {
                continue;
            }
            JsonNode action = e.getValue().path("action");
            double time;
            if (action.isObject()) {
                time = action.path("end").asDouble();
            } else if (e.getValue().path("behaviour").isObject()) {
                time = gameNow; // idle with a behaviour: starts now
            } else {
                continue;
            }
            if (time <= gameNow && (best == null || time < bestTime)) {
                best = e.getKey();
                bestTime = time;
            }
        }
        return best;
    }

    /** Starts the behaviour's next action at {@code startAt}; false (and a warning) if it can't. */
    private boolean start(String id, ObjectNode state, double startAt) {
        JsonNode behaviour = state.path("behaviour");
        String kind = behaviour.path("kind").asText();
        if ("repeat".equals(kind)) {
            JsonNode next = behaviour.path("action");
            if (!startable(id, state, next)) {
                return false;
            }
            String typeName = next.path("type").asText();
            ActionType t = types.get(typeName);
            JsonNode params = next.path("params");
            double duration = t.duration(params, state);
            begin(state, startAt, t, typeName, params, duration);
            return true;
        }
        if ("random".equals(kind)) {
            JsonNode choicesNode = behaviour.path("choices");
            if (!choicesNode.isArray() || choicesNode.size() == 0) {
                log.warn("{}: cannot start behaviour (kind {}, choices empty)", id, kind);
                return false;
            }
            List<Double> weights = new java.util.ArrayList<>();
            List<JsonNode> actions = new java.util.ArrayList<>();
            for (JsonNode choice : choicesNode) {
                JsonNode weightNode = choice.path("weight");
                if (!weightNode.isNumber() || !Double.isFinite(weightNode.asDouble()) || weightNode.asDouble() <= 0) {
                    log.warn("{}: cannot start behaviour (kind {}, invalid weight {})", id, kind, weightNode);
                    return false;
                }
                double w = weightNode.asDouble();
                weights.add(w);
                JsonNode actionNode = choice.path("action");
                if (!startable(id, state, actionNode)) {
                    return false;
                }
                actions.add(actionNode);
            }
            double u = generator.nextDouble();
            int index = Behaviours.pick(weights, u);
            JsonNode chosen = actions.get(index);
            String typeName = chosen.path("type").asText();
            ActionType t = types.get(typeName);
            JsonNode params = chosen.path("params");
            double duration = t.duration(params, state);
            ObjectNode action = begin(state, startAt, t, typeName, params, duration);
            ObjectNode draw = action.putObject("draw");
            draw.put("dist", "categorical");
            ArrayNode weightsArray = draw.putArray("weights");
            for (double w : weights) {
                weightsArray.add(w);
            }
            draw.put("u", u);
            draw.put("value", index);
            return true;
        }
        log.warn("{}: cannot start behaviour (kind {})", id, kind);
        return false;
    }

    private ObjectNode begin(ObjectNode state, double startAt, ActionType t, String typeName, JsonNode params, double duration) {
        ObjectNode action = state.putObject("action");
        action.put("type", typeName);
        action.set("params", params.deepCopy());
        action.put("start", startAt);
        action.put("end", startAt + duration);
        action.put("effect", t.effect().json());
        if (t.effect() == ActionType.Effect.START) {
            t.apply(params, state);
        }
        return action;
    }
    private boolean startable(String id, ObjectNode state, JsonNode next) {
        String typeName = next.path("type").asText();
        ActionType t = types.get(typeName);
        boolean allowed = false;
        for (JsonNode a : state.path("actions")) {
            allowed |= a.asText().equals(typeName);
        }
        if (t == null || !allowed) {
            log.warn("{}: action {} not startable (known {}, listed {})", id, typeName, t != null, allowed);
            return false;
        }
        double duration = t.duration(next.path("params"), state);
        if (!Double.isFinite(duration) || duration <= 0) {
            log.warn("{}: action {} has duration {}; not started", id, typeName, duration);
            return false;
        }
        return true;
    }
}
