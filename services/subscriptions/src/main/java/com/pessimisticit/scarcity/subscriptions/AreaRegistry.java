package com.pessimisticit.scarcity.subscriptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.MicrometerTracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * area id to sockets, plus the area's version map ({@code id -> version}, the max seen).
 *
 * <p>Locking: each {@link Area} is its own monitor, guarding its sockets, version map and the
 * pending/retired flags. Network calls to world happen outside it (under {@code Area.loadLock},
 * which also makes concurrent first subscribers share one load). Sends under the area monitor
 * only enqueue on the per-session decorator, so a slow client can't stall the area for long.
 * Ordering guarantee: a socket is added, and gets {@code subscribed} + {@code digest}, in one
 * critical section; a batch's versions are merged and its recipients snapshotted in one
 * critical section. So a socket sees every batch applied after its digest and none before it.
 *
 * <p>Turns (docs/websocket.md, "turn"): the area's {@code activeEntity} is kept from the load and from
 * every batch that carries the area (its JSON is the whole area). It goes as a {@code turn}
 * frame to the sockets whose principal is its controller, and to no other: after
 * {@code subscribed} + {@code digest}, after a {@code changes} frame that carries the area, and
 * with every periodic digest.
 */
@Component
public class AreaRegistry {

    private static final Logger log = LoggerFactory.getLogger(AreaRegistry.class);

    static final class Area {
        final String id;
        final Set<Client> clients;
        final Map<String, Long> versions;
        /** Highest ChangeBatch.logSeq seen for the area; null until one is seen. Guarded by the area monitor. */
        Long logSeq;
        /** The area's {@code activeEntity} (null: nobody, or not known yet), and the area version it is from. */
        JsonNode activeEntity;
        long upVersion;
        final Object loadLock;
        boolean loaded;
        /** Subscribers that are loading and will join; keeps the area alive. */
        int pending;
        boolean retired;

        Area(String id) {
            this.clients = new HashSet<>();
            this.versions = new HashMap<>();
            this.upVersion = -1;
            this.loadLock = new Object();
            this.id = id;
        }

        /**
         * Caller holds this monitor. Merges the batch's versions, logSeq and who is the active entity; true if it
         * carried this area's state.
         */
        boolean merge(ChangeBatch batch) {
            boolean carriesArea = false;
            for (ChangeBatch.Change ch : batch.changes()) {
                if (ch.deleted()) {
                    versions.remove(ch.id());
                    continue;
                }
                if (ch.version() > 0) {
                    versions.merge(ch.id(), ch.version(), Math::max);
                }
                if (id.equals(ch.id()) && ch.hasBody()) {
                    carriesArea |= setActiveEntity(ch.json().get("activeEntity"), ch.version());
                }
            }
            if (batch.logSeq() != null && (logSeq == null || batch.logSeq() > logSeq)) {
                logSeq = batch.logSeq();
            }
            return carriesArea;
        }

        /**
         * Caller holds this monitor. Keeps {@code next} (null or not an object: nobody is active) if
         * its area version is no older than what is kept.
         */
        boolean setActiveEntity(JsonNode next, long version) {
            if (version < upVersion) {
                return false;
            }
            activeEntity = next != null && next.isObject() ? next.deepCopy() : null;
            upVersion = version;
            return true;
        }

        /** Caller holds this monitor. */
        List<TextMessage> turnFrames(ObjectMapper json, Client c) {
            return TurnFrames.of(json, id, activeEntity, versions, c);
        }
    }

    private final ConcurrentHashMap<String, Area> areas;
    private final WorldClient worldClient;
    private final ObjectMapper json;
    private final Tracer tracer;

    AreaRegistry(WorldClient worldClient, ObjectMapper json, Tracer tracer) {
        this.areas = new ConcurrentHashMap<>();
        this.worldClient = worldClient;
        this.json = json;
        this.tracer = tracer;
    }

    /**
     * Subscribes {@code c} (idempotent). The first subscriber loads the version map from world with
     * {@code c}'s token. Sends {@code subscribed} then {@code digest}. Throws
     * {@link WorldClient.WorldException} (or a RestClient exception) if the load fails; nothing
     * is subscribed then.
     */
    void subscribe(Client c, String areaId) {
        Area a;
        while (true) {
            a = areas.computeIfAbsent(areaId, Area::new);
            synchronized (a) {
                if (a.retired) {
                    continue;
                }
                if (a.clients.contains(c)) {
                    c.send(subscribed(areaId));
                    c.send(digest(a));
                    a.turnFrames(json, c).forEach(c::send);
                    return;
                }
                a.pending++;
                break;
            }
        }
        try {
            ensureLoaded(a, c.token);
        } catch (RuntimeException e) {
            synchronized (a) {
                a.pending--;
                retireIfIdle(a);
            }
            throw e;
        }
        synchronized (a) {
            a.pending--;
            a.clients.add(c);
            c.areas.add(areaId);
            if (!c.isOpen()) {
                // Closed while loading; its cleanup may already have run.
                a.clients.remove(c);
                c.areas.remove(areaId);
                retireIfIdle(a);
                return;
            }
            c.send(subscribed(areaId));
            c.send(digest(a));
            a.turnFrames(json, c).forEach(c::send);
        }
    }

    /** Returns true if {@code c} was subscribed. */
    boolean unsubscribe(Client c, String areaId) {
        Area a = areas.get(areaId);
        boolean was = c.areas.remove(areaId);
        if (a != null) {
            synchronized (a) {
                was |= a.clients.remove(c);
                retireIfIdle(a);
            }
        }
        return was;
    }

    /** Drops {@code c} from every area (disconnect). */
    void remove(Client c) {
        for (String areaId : new ArrayList<>(c.areas)) {
            unsubscribe(c, areaId);
        }
    }

    private void retireIfIdle(Area a) {
        if (a.clients.isEmpty() && a.pending == 0) {
            a.retired = true;
            areas.remove(a.id, a);
        }
    }

    private void ensureLoaded(Area a, String token) {
        synchronized (a.loadLock) {
            synchronized (a) {
                if (a.loaded) {
                    return;
                }
            }
            Span span = tracer.nextSpan().name("subscriptions.load-area").tag("area", a.id).start();
            WorldClient.Loaded fetched;
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                fetched = worldClient.fetch(a.id, token);
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
            synchronized (a) {
                // Batches that arrived while loading are already in the map: keep the max.
                fetched.versions().forEach((id, v) -> a.versions.merge(id, v, Math::max));
                a.setActiveEntity(fetched.activeEntity(), fetched.areaVersion());
                a.loaded = true;
            }
        }
    }

    /** Called on the LISTEN thread for every committed batch. */
    void onBatch(ChangeBatch batch) {
        if (batch.area() == null || batch.changes().isEmpty()
                || batch.changes().stream().anyMatch(c -> c == null || !c.json().hasNonNull("id")
                        || !c.json().hasNonNull("resourceKind"))) {
            log.warn("invalid resource_changes batch ignored (area={})", batch.area());
            return;
        }
        Area a = areas.get(batch.area());
        if (a == null) {
            return;
        }
        try {
            MicrometerTracing.inChildSpan(tracer, batch, "subscriptions.fanout", () -> {
                fanOut(a, batch);
                return null;
            });
        } catch (Exception e) {
            log.warn("fan-out of a batch for {} failed", batch.area(), e);
        }
    }

    private void fanOut(Area a, ChangeBatch batch) {
        List<Client> targets;
        boolean carriesArea;
        synchronized (a) {
            if (a.retired) {
                return;
            }
            carriesArea = a.merge(batch);
            targets = new ArrayList<>(a.clients);
        }
        if (targets.isEmpty()) {
            return;
        }
        ObjectNode frame = json.createObjectNode();
        frame.put("type", "changes");
        frame.setAll((ObjectNode) json.valueToTree(batch));
        TextMessage message = new TextMessage(frame.toString());
        for (Client t : targets) {
            t.send(message);
        }
        if (carriesArea) {
            sendTurns(a, targets);
        }
    }

    /** Sends each of {@code targets} its turn frame, if it controls the active entity. */
    private void sendTurns(Area a, List<Client> targets) {
        for (Client t : targets) {
            List<TextMessage> frames;
            synchronized (a) {
                frames = a.turnFrames(json, t);
            }
            frames.forEach(t::send);
        }
    }

    /** Sends each socket one digest per subscribed area from the in-memory maps. */
    void sendDigests() {
        for (Area a : areas.values()) {
            List<Client> targets;
            TextMessage message;
            synchronized (a) {
                if (a.retired || a.clients.isEmpty() || !a.loaded) {
                    continue;
                }
                targets = new ArrayList<>(a.clients);
                message = digest(a);
            }
            for (Client t : targets) {
                t.send(message);
            }
            sendTurns(a, targets);
        }
    }

    /** Caller holds the area's monitor. */
    private TextMessage digest(Area a) {
        ObjectNode frame = json.createObjectNode();
        frame.put("type", "digest");
        frame.put("area", a.id);
        ObjectNode versions = frame.putObject("versions");
        a.versions.forEach((k, v) -> versions.put(k, v.longValue()));
        if (a.logSeq != null) {
            frame.put("logSeq", a.logSeq.longValue());
        }
        return new TextMessage(frame.toString());
    }

    private TextMessage subscribed(String areaId) {
        ObjectNode frame = json.createObjectNode();
        frame.put("type", "subscribed");
        frame.put("area", areaId);
        return new TextMessage(frame.toString());
    }

    // Visible for tests.
    boolean hasArea(String id) {
        return areas.containsKey(id);
    }
}
