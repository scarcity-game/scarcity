package com.pessimisticit.scarcity.subscriptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * One real Postgres (as in prod), one JWKS endpoint (as auth's), one stub world (resource API)
 * and a WebSocket test client, for the whole test JVM.
 */
final class TestSupport {

    static final ObjectMapper JSON = new ObjectMapper();
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
    static final RSAKey KEY;
    static final RSAKey OTHER_KEY;
    static final HttpServer JWKS;
    static final StubWorld WORLD;

    static {
        POSTGRES.start();
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            OTHER_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks.json", ex -> {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            JWKS.start();
            WORLD = new StubWorld();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestSupport() {
    }

    static String jwksUri() {
        return "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks.json";
    }

    static String token(RSAKey signer, String issuer, long ttlMillis) {
        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer)
                    .subject("00000000-0000-4000-8000-0000000000aa").claim("username", "tester")
                    .issueTime(new Date(now)).expirationTime(new Date(now + ttlMillis)).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(signer));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** As auth's {@code POST /api/auth/service-token} mints them: no username, {@code kind=service}. */
    static String serviceToken(String principal) {
        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("scarcity-auth").subject(principal)
                    .claim("kind", "service").issueTime(new Date(now)).expirationTime(new Date(now + 900_000)).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String validToken() {
        return token(KEY, "scarcity-auth", 3_600_000);
    }

    /** Area id with a recognisable suffix. */
    static String areaId(int n) {
        return String.format("area:00000000-0000-4000-8000-%012d", n);
    }

    static String entityId(int n) {
        return String.format("entity:00000000-0000-4000-8000-%012d", n);
    }

    /**
     * A tiny stand-in for world's resource API: {@code GET /api/world/resources/{id}} and
     * {@code /children?size=&page=}. Requires a Bearer token. Area ids registered with
     * {@link #area}; {@code 401}/{@code 500} behaviours by magic area numbers.
     */
    static final class StubWorld {
        static final int UNAUTHORIZED_AREA = 998;
        static final int BROKEN_AREA = 999;

        final HttpServer server;
        final Map<String, Long> areaVersions;
        final Map<String, Map<String, Long>> children;
        final Map<String, ObjectNode> states;
        final Map<String, AtomicInteger> calls;
        final List<String> authHeaders;

        StubWorld() throws IOException {
            this.areaVersions = new ConcurrentHashMap<>();
            this.children = new ConcurrentHashMap<>();
            this.states = new ConcurrentHashMap<>();
            this.calls = new ConcurrentHashMap<>();
            this.authHeaders = new CopyOnWriteArrayList<>();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/world/resources/", this::handle);
            server.createContext("/api/world/resources", this::list);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void area(String id, long version, Map<String, Long> kids) {
            areaVersions.put(id, version);
            children.put(id, kids);
        }

        void area(String id, long version, Map<String, Long> kids, ObjectNode state) {
            states.put(id, state);
            area(id, version, kids);
        }

        int callsFor(String id) {
            AtomicInteger n = calls.get(id);
            return n == null ? 0 : n.get();
        }

        private void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath().substring("/api/world/resources/".length());
            String query = ex.getRequestURI().getQuery();
            boolean kids = path.endsWith("/children");
            String id = kids ? path.substring(0, path.length() - "/children".length()) : path;
            calls.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth);
            if (auth == null || !auth.startsWith("Bearer ") || id.equals(areaId(UNAUTHORIZED_AREA))) {
                reply(ex, 401, "");
                return;
            }
            if (id.equals(areaId(BROKEN_AREA))) {
                reply(ex, 500, "{\"error\":\"boom\"}");
                return;
            }
            Long version = areaVersions.get(id);
            if (version == null) {
                reply(ex, 404, "{\"error\":\"not found\"}");
                return;
            }
            ObjectNode out = JSON.createObjectNode();
            if (!kids) {
                out.put("resourceKind", "area").put("id", id).put("version", version);
                out.putArray("children");
                ObjectNode state = states.get(id);
                if (state != null) {
                    out.setAll(state.deepCopy());
                }
            } else {
                int size = 50;
                int page = 0;
                for (String kv : query == null ? new String[0] : query.split("&")) {
                    if (kv.startsWith("size=")) {
                        size = Math.min(200, Integer.parseInt(kv.substring(5)));
                    } else if (kv.startsWith("page=")) {
                        page = Integer.parseInt(kv.substring(5));
                    }
                }
                List<Map.Entry<String, Long>> all = new ArrayList<>(
                        new java.util.TreeMap<>(children.get(id)).entrySet());
                ArrayNode items = out.putArray("items");
                for (int i = page * size; i < Math.min(all.size(), (page + 1) * size); i++) {
                    items.addObject().put("resourceKind", "entity").put("id", all.get(i).getKey())
                            .put("version", all.get(i).getValue());
                }
                out.put("page", page).put("size", size).put("total", all.size());
            }
            reply(ex, 200, out.toString());
        }

        /** {@code GET /api/world/resources?type=area&size=&page=}: every registered area, with its state. */
        private void list(HttpExchange ex) throws IOException {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.startsWith("Bearer ")) {
                reply(ex, 401, "");
                return;
            }
            calls.computeIfAbsent("?type=area", k -> new AtomicInteger()).incrementAndGet();
            int size = 50;
            int page = 0;
            String query = ex.getRequestURI().getQuery();
            for (String kv : query == null ? new String[0] : query.split("&")) {
                if (kv.startsWith("size=")) {
                    size = Math.min(200, Integer.parseInt(kv.substring(5)));
                } else if (kv.startsWith("page=")) {
                    page = Integer.parseInt(kv.substring(5));
                }
            }
            List<String> ids = new ArrayList<>(new java.util.TreeSet<>(areaVersions.keySet()));
            ObjectNode out = JSON.createObjectNode();
            ArrayNode items = out.putArray("items");
            for (int i = page * size; i < Math.min(ids.size(), (page + 1) * size); i++) {
                String id = ids.get(i);
                ObjectNode item = items.addObject().put("resourceKind", "area").put("id", id)
                        .put("version", areaVersions.get(id));
                ObjectNode state = states.get(id);
                if (state != null) {
                    item.setAll(state.deepCopy());
                }
            }
            out.put("page", page).put("size", size).put("total", ids.size());
            reply(ex, 200, out.toString());
        }

        private static void reply(HttpExchange ex, int status, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                ex.getResponseBody().write(b);
            }
            ex.close();
        }
    }

    /** A real WebSocket client collecting JSON frames and the close code. */
    static final class WsClient implements WebSocket.Listener {
        final BlockingQueue<JsonNode> frames;
        final List<JsonNode> seen;
        /** Every frame as the server sent it, in order. */
        final List<String> raw;
        final CompletableFuture<Integer> closed;
        private final StringBuilder buf;
        WebSocket ws;

        static WsClient connect(int port) throws Exception {
            WsClient c = new WsClient();
            c.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/subscriptions/ws"), c)
                    .get(10, TimeUnit.SECONDS);
            return c;
        }

        void send(String text) throws Exception {
            ws.sendText(text, true).get(5, TimeUnit.SECONDS);
        }

        void auth(String token) throws Exception {
            send("{\"type\":\"auth\",\"token\":\"" + token + "\"}");
        }

        /** Next frame matching {@code p}; earlier non-matching frames are dropped. */
        JsonNode await(Predicate<JsonNode> p, long timeoutMillis) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                JsonNode f = left <= 0 ? null : frames.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) {
                    throw new AssertionError("no matching frame in " + timeoutMillis + " ms; saw " + seen);
                }
                if (p.test(f)) {
                    return f;
                }
            }
        }

        JsonNode awaitType(String type) throws Exception {
            return await(f -> type.equals(f.path("type").asText()), 8000);
        }

        /** Next frame, whatever it is. */
        JsonNode next() throws Exception {
            JsonNode f = frames.poll(8, TimeUnit.SECONDS);
            if (f == null) {
                throw new AssertionError("no frame; saw " + seen);
            }
            return f;
        }

        int awaitClose(long timeoutMillis) throws Exception {
            return closed.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                try {
                    JsonNode f = JSON.readTree(buf.toString());
                    raw.add(buf.toString());
                    seen.add(f);
                    frames.add(f);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                buf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        final AtomicInteger pings;

        WsClient() {
            this.frames = new LinkedBlockingQueue<>();
            this.seen = new CopyOnWriteArrayList<>();
            this.raw = new CopyOnWriteArrayList<>();
            this.closed = new CompletableFuture<>();
            this.buf = new StringBuilder();
            this.pings = new AtomicInteger();
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
            pings.incrementAndGet();
            webSocket.request(1);
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }
}
