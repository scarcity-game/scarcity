package com.pessimisticit.scarcity.subscriptions;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;

import jakarta.annotation.PreDestroy;

/**
 * The turn feed (docs/websocket.md, {@code "turns": true}): a socket that subscribes to it is
 * subscribed to every area whose {@code activeEntity} it controls, wherever that area is, so a
 * controller hears of turns in areas made after it connected. On subscribe it reads every area
 * from world with the socket's token; after that, each batch whose area update sets an active
 * entity the socket controls joins it to that area. A reload (a crossing; a new level's active
 * entity is in no delta) or a stub of the area reads the area from world with the socket's token
 * and joins if the socket controls its active entity. Joining is an ordinary {@link AreaRegistry}
 * subscribe ({@code subscribed}, {@code checksum}, then the {@code turn}), on one thread of its
 * own, never on the LISTEN thread. A joined area stays subscribed until the feed is unsubscribed
 * or the socket closes.
 */
@Component
public class TurnFeed {

    private static final Logger log = LoggerFactory.getLogger(TurnFeed.class);

    private final AreaRegistry areaRegistry;
    private final WorldClient worldClient;
    private final ObjectMapper json;
    private final Set<Client> feeds;
    private final ExecutorService joins;

    TurnFeed(AreaRegistry areaRegistry, WorldClient worldClient, ObjectMapper json) {
        this.feeds = ConcurrentHashMap.newKeySet();
        this.joins = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("subscriptions-turn-feed").factory());
        this.areaRegistry = areaRegistry;
        this.worldClient = worldClient;
        this.json = json;
    }

    /** {@code c} subscribes to the feed (idempotent): {@code subscribed}, then the areas it is up in. */
    void subscribe(Client c) {
        feeds.add(c);
        c.send(frame("subscribed"));
        joins.execute(() -> joinAll(c));
    }

    /** {@code c} leaves the feed and every area it joined through it. */
    void unsubscribe(Client c) {
        feeds.remove(c);
        for (String area : List.copyOf(c.fedAreas)) {
            c.fedAreas.remove(area);
            areaRegistry.unsubscribe(c, area);
        }
        c.send(frame("unsubscribed"));
    }

    /** The socket closed; the registry drops its areas. */
    void remove(Client c) {
        feeds.remove(c);
    }

    /** Called on the LISTEN thread for every committed batch: queues a join for each feed that is up in the area. */
    void onBatch(ChangeBatch batch) {
        if (feeds.isEmpty() || batch.area() == null) {
            return;
        }
        if (batch.reloading() || areaIsStub(batch)) {
            readAndJoin(batch.area());
            return;
        }
        JsonNode activeEntity = activeEntityOf(batch);
        if (activeEntity == null) {
            return;
        }
        for (Client c : feeds) {
            if (!c.areas.contains(batch.area()) && TurnFrames.controls(activeEntity.path("controller"), c)) {
                joins.execute(() -> join(c, batch.area()));
            }
        }
    }

    /** Queues, for each feed not in {@code area}, a read of the area and a join if it is up there. */
    private void readAndJoin(String area) {
        for (Client c : feeds) {
            if (!c.areas.contains(area)) {
                joins.execute(() -> joinIfUp(c, area));
            }
        }
    }

    /** The {@code activeEntity} the batch's update of its area sets; null if it sets none. */
    private static JsonNode activeEntityOf(ChangeBatch batch) {
        for (ChangeBatch.Change ch : batch.changes()) {
            if (ch != null && ch.kind() == ChangeBatch.Change.Kind.UPDATE && batch.area().equals(ch.id())) {
                JsonNode up = ch.set().get("activeEntity");
                return up != null && up.isObject() ? up : null;
            }
        }
        return null;
    }

    private static boolean areaIsStub(ChangeBatch batch) {
        return batch.changes().stream()
                .anyMatch(ch -> ch != null && batch.area().equals(ch.id()) && ch.kind() == ChangeBatch.Change.Kind.STUB);
    }

    /** Reads {@code area} with {@code c}'s token and joins {@code c} to it if {@code c} controls its active entity. */
    private void joinIfUp(Client c, String area) {
        if (!c.isOpen() || !feeds.contains(c) || c.areas.contains(area)) {
            return;
        }
        JsonNode up;
        try {
            up = worldClient.area(area, c.token).get("activeEntity");
        } catch (RuntimeException e) {
            log.warn("turn feed of {}: could not read {}: {}", c.id(), area, e.toString());
            return;
        }
        if (up != null && up.isObject() && TurnFrames.controls(up.path("controller"), c)) {
            join(c, area);
        }
    }

    private void joinAll(Client c) {
        List<JsonNode> areas;
        try {
            areas = worldClient.areas(c.token);
        } catch (RuntimeException e) {
            log.warn("turn feed of {}: could not list the areas: {}", c.id(), e.toString());
            error(c, "could not list areas");
            return;
        }
        for (JsonNode area : areas) {
            JsonNode up = area.get("activeEntity");
            if (up != null && up.isObject() && TurnFrames.controls(up.path("controller"), c)) {
                join(c, area.path("id").asText());
            }
        }
    }

    private void join(Client c, String area) {
        if (!c.isOpen() || !feeds.contains(c) || c.areas.contains(area)) {
            return;
        }
        try {
            c.fedAreas.add(area);
            areaRegistry.subscribe(c, area);
        } catch (RuntimeException e) {
            c.fedAreas.remove(area);
            log.warn("turn feed of {}: could not join {}: {}", c.id(), area, e.toString());
            error(c, "could not load area " + area);
        }
    }

    private void error(Client c, String message) {
        ObjectNode out = json.createObjectNode();
        out.put("type", "error");
        out.put("message", message);
        c.send(new TextMessage(out.toString()));
    }

    private TextMessage frame(String type) {
        ObjectNode out = json.createObjectNode();
        out.put("type", type);
        out.put("turns", true);
        return new TextMessage(out.toString());
    }

    @PreDestroy
    void stop() {
        joins.shutdownNow();
    }
}
