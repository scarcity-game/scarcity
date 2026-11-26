package com.pessimisticit.scarcity.subscriptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.MicrometerTracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * area id to sockets, plus the area's version map ({@code id -> version}, the max seen), from which the
 * version checksum is computed (docs/resources.md, "Versions"). An area is here only while a socket is
 * subscribed to it (or loading): the first subscriber LISTENs on the area's channel and then loads the map;
 * the last one to leave drops the map and UNLISTENs (#378). So a pod hears only the areas its sockets want,
 * and a pod that starts mid-game builds each map the same way the first pod did. Two pods may serve one area
 * (while pods are added or removed); each keeps its own map from the same batches, so both are right.
 *
 * <p>Locking: each {@link Area} is its own monitor, guarding its sockets, version map and the
 * pending/retired flags. Network calls to world happen outside it (under {@code Area.loadLock},
 * which also makes concurrent first subscribers share one load). Sends under the area monitor
 * only enqueue on the per-session decorator, so a slow client can't stall the area for long.
 * Ordering guarantee: a socket is added, and gets {@code subscribed} + {@code checksum}, in one
 * critical section; a batch's versions are merged and its recipients snapshotted in one
 * critical section. So a socket sees every batch applied after its checksum and none before it.
 *
 * <p>Turns (docs/websocket.md, "turn"): the area's {@code activeEntity} is kept from the load and from
 * the area's updates ({@code set.activeEntity}, or {@code unset}). A {@code reload}, or a stub of the
 * area, reads the area again from world, with a subscriber's token, on the LISTEN thread (both are
 * rare: a crossing, an area too big to send). It goes as a {@code turn} frame to the sockets whose
 * principal is its controller, and to no other: after {@code subscribed} + {@code checksum}, after
 * every {@code changes} or {@code reload} frame of the area, and with every periodic checksum.
 */
@Component
public class AreaRegistry {

    private static final Logger log = LoggerFactory.getLogger(AreaRegistry.class);

    private final ConcurrentHashMap<String, Area> areas;
    private final WorldClient worldClient;
    private final AreaListens areaListens;
    private final ObjectMapper json;
    private final Tracer tracer;

    AreaRegistry(WorldClient worldClient, AreaListens areaListens, ObjectMapper json, Tracer tracer) {
        this.areas = new ConcurrentHashMap<>();
        this.worldClient = worldClient;
        this.areaListens = areaListens;
        this.json = json;
        this.tracer = tracer;
    }

    /**
     * Subscribes {@code c} (idempotent). The first subscriber loads the version map from world with
     * {@code c}'s token. Sends {@code subscribed} then {@code checksum}. Throws
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
                    c.send(a.checksum(json));
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
            c.send(a.checksum(json));
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

    /** Caller holds the area's monitor. The last socket gone: drop the version map and stop the area's batches. */
    private void retireIfIdle(Area a) {
        if (a.clients.isEmpty() && a.pending == 0) {
            a.retired = true;
            areas.remove(a.id, a);
            areaListens.release(a);
        }
    }

    private void ensureLoaded(Area a, String token) {
        synchronized (a.loadLock) {
            synchronized (a) {
                if (a.loaded) {
                    return;
                }
            }
            // Before the load, so a batch committed while loading is received and merged (keeping the max).
            areaListens.listen(a);
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
                a.mergeLogSeq(fetched.logSeq());
                a.loaded = true;
            }
        }
    }

    /** Called on the LISTEN thread for every committed batch. */
    void onBatch(ChangeBatch batch) {
        if (batch.area() == null || (batch.changes().isEmpty() && !batch.reloading())
                || batch.changes().stream().anyMatch(c -> c == null || !c.json().hasNonNull("id")
                        || !c.json().hasNonNull("resourceKind"))) {
            log.warn("invalid batch ignored (area={})", batch.area());
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
        boolean stale;
        synchronized (a) {
            if (a.retired) {
                return;
            }
            stale = batch.reloading() || a.missed(batch) | a.merge(batch);
            a.mergeLogSeq(batch.logSeq());
            targets = new ArrayList<>(a.clients);
        }
        if (targets.isEmpty()) {
            return;
        }
        if (stale) {
            reread(a, targets.getFirst().token);
        }
        ObjectNode frame = json.createObjectNode();
        if (batch.reloading()) {
            frame.put("type", "reload");
            frame.put("area", batch.area());
            if (batch.logSeq() != null) {
                frame.put("logSeq", batch.logSeq().longValue());
            }
            if (batch.traceparent() != null) {
                frame.put("traceparent", batch.traceparent());
            }
        } else {
            frame.put("type", "changes");
            frame.setAll((ObjectNode) json.valueToTree(batch));
        }
        TextMessage message = new TextMessage(frame.toString());
        for (Client t : targets) {
            t.send(message);
        }
        sendTurns(a, targets);
    }

    /**
     * Reads the area's versions and {@code activeEntity} again (a reload, a stub of the area, or a batch this
     * service missed, by the logSeq chain) with
     * {@code token}, a subscriber's. On failure the map stays as it was; the next checksum shows clients
     * what is off.
     */
    private void reread(Area a, String token) {
        try {
            WorldClient.Loaded fetched = worldClient.fetch(a.id, token);
            synchronized (a) {
                a.versions.clear();
                a.versions.putAll(fetched.versions());
                a.setActiveEntity(fetched.activeEntity(), fetched.areaVersion());
                a.mergeLogSeq(fetched.logSeq());
            }
        } catch (RuntimeException e) {
            log.warn("could not read {} again after a reload or a stub: {}", a.id, e.toString());
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

    /** Sends each socket one checksum per subscribed area from the in-memory maps. */
    void sendChecksums() {
        for (Area a : areas.values()) {
            List<Client> targets;
            TextMessage message;
            synchronized (a) {
                if (a.retired || a.clients.isEmpty() || !a.loaded) {
                    continue;
                }
                targets = new ArrayList<>(a.clients);
                message = a.checksum(json);
            }
            for (Client t : targets) {
                t.send(message);
            }
            sendTurns(a, targets);
        }
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
