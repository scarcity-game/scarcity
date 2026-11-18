package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A stand-in for world's API, and auth's service-token exchange, on a random port: entities
 * by id, a scripted answer to {@code POST /api/world/intents}, and {@code token-1},
 * {@code token-2}, … for {@link #KEY}. Records every request with when it arrived
 * and when its answer was sent.
 */
public final class StubWorld implements AutoCloseable {

    /** The key auth accepts. */
    public static final String KEY = Fixtures.KEY;
    private static final String CHILDREN = "/children";
    public static final String AREA = "area:00000000-0000-4000-8000-000000000002";
    public static final String ORBITER = "entity:00000000-0000-4000-8000-000000000004";
    public static final String PILLAR = "entity:00000000-0000-4000-8000-000000000003";
    public static final String IDLE_ORBITER = "{\"entityKind\":\"npc\",\"prototype\":\"orbiter\",\"name\":\"orbiter\",\"position\":{\"x\":8.0,\"y\":4.0},"
            + "\"speed\":0.5,\"actions\":[\"step\",\"wait\",\"throw\"],"
            + "\"controller\":{\"kind\":\"service\",\"principal\":\"orbital\"}}";
    public static final String PILLAR_STATE =
            "{\"entityKind\":\"fixed\",\"prototype\":\"pillar\",\"name\":\"pillar\",\"position\":{\"x\":5.0,\"y\":4.0},\"radius\":0.5,\"hp\":100.0,\"damageable\":true}";
    public static final String ACCEPTED = "{\"area\":\"area:00000000-0000-4000-8000-000000000002\",\"time\":1.0,"
            + "\"logSeq\":3,\"version\":2}";

    /** One request: what came in and when, and when the answer went out. */
    public record Request(String method, String path, Map<String, List<String>> headers, String body,
            Instant received, Instant answered) {

        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    /** A scripted answer. */
    public record Answer(int status, String body) {
        public static Answer ok(String body) {
            return new Answer(200, body);
        }

        public static Answer error(int status, String message) {
            return new Answer(status, message == null ? "" : "{\"error\":\"" + message + "\"}");
        }
    }

    public final List<Request> requests;
    private final Map<String, Answer> entities;
    private final Map<String, Long> versions;
    private final Map<String, List<String>> children = new ConcurrentHashMap<>();
    private final AtomicInteger tokens;
    private volatile long expiresIn;
    private volatile Function<String, Answer> intents;
    private volatile Duration delay;
    private final HttpServer server;

    public StubWorld() throws IOException {
        this.requests = new CopyOnWriteArrayList<>();
        this.entities = new ConcurrentHashMap<>();
        this.versions = new ConcurrentHashMap<>();
        this.tokens = new AtomicInteger();
        this.expiresIn = 900;
        this.intents = body -> Answer.ok(ACCEPTED);
        this.delay = Duration.ZERO;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/world/resources/", this::resource);
        server.createContext("/api/world/intents", this::intent);
        server.createContext("/api/auth/service-token", this::token);
        server.start();
        entity(ORBITER, 1, IDLE_ORBITER);
        entity(PILLAR, 1, PILLAR_STATE);
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void entity(String id, long version, String state) {
        versions.put(id, version);
        entities.put(id, Answer.ok("{\"resourceKind\":\"entity\",\"id\":\"" + id + "\",\"version\":" + version
                + ",\"children\":[]," + state.trim().substring(1)));
    }

    /** Serves {@code area} (it carries its id and version) at {@code GET /api/world/resources/{id}}. */
    public void area(Area area) {
        entities.put(area.id(), Answer.ok(WorldJson.toJson(area)));
    }

    /** The version {@link #entity} last set for {@code id}. */
    public long version(String id) {
        return versions.get(id);
    }

    /** The {@code turn} frame for {@code id} at its current version. */
    public TurnFrame turn(String id) {
        return new TurnFrame(AREA, id, version(id), 0.0);
    }

    /** How many service tokens were issued. */
    public int tokensIssued() {
        return tokens.get();
    }

    /** The lifetime of the tokens issued from now. */
    public void expiresIn(long seconds) {
        this.expiresIn = seconds;
    }

    public void entityAnswer(String id, Answer answer) {
        entities.put(id, answer);
    }

    public void intents(Function<String, Answer> answer) {
        this.intents = answer;
    }

    /** Every answer is sent this long after its request arrived. */
    public void delay(Duration delay) {
        this.delay = delay;
    }

    /** The intents posted. */
    public List<Request> posts() {
        return requests.stream().filter(r -> r.path().equals("/api/world/intents")).toList();
    }

    /** The requests to world (not to auth). */
    public List<Request> worldRequests() {
        return requests.stream().filter(r -> r.path().startsWith("/api/world/")).toList();
    }

    /** Serves {@code ids} (set with {@link #entity}) at {@code GET /api/world/resources/{area}/children}. */
    public void children(String area, List<String> ids) {
        children.put(area, List.copyOf(ids));
    }

    private void resource(HttpExchange ex) throws IOException {
        String id = ex.getRequestURI().getPath().substring("/api/world/resources/".length());
        if (id.endsWith(CHILDREN)) {
            answer(ex, page(id.substring(0, id.length() - CHILDREN.length())));
            return;
        }
        answer(ex, entities.getOrDefault(id, Answer.error(404, "no resource " + id)));
    }

    private Answer page(String area) {
        List<String> ids = children.getOrDefault(area, List.of());
        String items = String.join(",", ids.stream().map(i -> entities.get(i).body()).toList());
        return Answer.ok("{\"items\":[" + items + "],\"page\":0,\"size\":200,\"total\":" + ids.size() + "}");
    }

    private void token(HttpExchange ex) throws IOException {
        if (!KEY.equals(ex.getRequestHeaders().getFirst("X-Api-Key"))) {
            answer(ex, new Answer(401, "{\"title\":\"Unauthorized\",\"status\":401}"));
            return;
        }
        answer(ex, Answer.ok("{\"token\":\"token-" + tokens.incrementAndGet() + "\",\"expiresIn\":" + expiresIn + "}"));
    }

    private void intent(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        answer(ex, body, intents.apply(body));
    }

    private void answer(HttpExchange ex, Answer answer) throws IOException {
        answer(ex, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), answer);
    }

    private void answer(HttpExchange ex, String requestBody, Answer answer) throws IOException {
        Instant received = Instant.now();
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        Instant answered = Instant.now();
        requests.add(new Request(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                Map.copyOf(ex.getRequestHeaders()), requestBody, received, answered));
        ex.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
