package com.pessimisticit.scarcity.subscriptions;

import static com.pessimisticit.scarcity.subscriptions.TestSupport.JSON;
import static com.pessimisticit.scarcity.subscriptions.TestSupport.WORLD;
import static com.pessimisticit.scarcity.subscriptions.TestSupport.areaId;
import static com.pessimisticit.scarcity.subscriptions.TestSupport.entityId;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;
import com.pessimisticit.scarcity.subscriptions.TestSupport.WsClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.endpoint.health.show-details=always",
        "scarcity.subscriptions.auth-timeout=500ms",
        "scarcity.subscriptions.digest-interval=2s",
        "scarcity.subscriptions.ping-interval=1s"})
@AutoConfigureObservability // Boot tests switch metrics export off by default
class SubscriptionsIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestSupport.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", TestSupport.POSTGRES::getUsername);
        r.add("spring.datasource.password", TestSupport.POSTGRES::getPassword);
        r.add("scarcity.subscriptions.jwks-uri", TestSupport::jwksUri);
        r.add("scarcity.subscriptions.world-url", WORLD::url);
    }

    @Autowired ChangePublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired AreaRegistry registry;
    @LocalServerPort int port;
    @LocalManagementPort int mgmtPort;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(int p, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    WsClient authed() throws Exception {
        WsClient c = WsClient.connect(port);
        c.auth(TestSupport.validToken());
        assertThat(c.awaitType("ready").path("type").asText()).isEqualTo("ready");
        return c;
    }

    void publish(String area, String entity, long version, double x) {
        ObjectNode patch = JSON.createObjectNode().put("x", x);
        tx.executeWithoutResult(s -> publisher.publish(new ChangeBatch(area,
                List.of(new ChangeBatch.Change(entity, ResourceType.ENTITY, version, patch)), null)));
    }

    // ---- boot ----

    @Test
    void readinessIsUpOnceListeningAndNoResourceApiIsServed() throws Exception {
        HttpResponse<String> ready = get(mgmtPort, "/actuator/health/readiness");
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ready.body()).path("status").asText()).isEqualTo("UP");
        HttpResponse<String> full = get(mgmtPort, "/actuator/health");
        assertThat(full.body()).contains("changeListener").contains("\"db\"");
        assertThat(get(port, "/resources?type=game").statusCode()).isEqualTo(404);
        assertThat(get(port, "/api/subscriptions/resources?type=game").statusCode()).isEqualTo(404);
        assertThat(get(mgmtPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    // ---- auth ----

    @Test
    void firstFrameThatIsNotAuthCloses4400() throws Exception {
        WsClient c = WsClient.connect(port);
        c.send("{\"type\":\"subscribe\",\"area\":\"" + areaId(1) + "\"}");
        assertThat(c.awaitClose(5000)).isEqualTo(4400);
        WsClient junk = WsClient.connect(port);
        junk.send("not json");
        assertThat(junk.awaitClose(5000)).isEqualTo(4400);
        WsClient ping = WsClient.connect(port);
        ping.send("{\"type\":\"ping\"}");
        assertThat(ping.awaitClose(5000)).isEqualTo(4400);
    }

    @Test
    void noFrameWithinTheTimeoutCloses4401() throws Exception {
        WsClient c = WsClient.connect(port);
        long start = System.currentTimeMillis();
        assertThat(c.awaitClose(5000)).isEqualTo(4401);
        assertThat(System.currentTimeMillis() - start).isBetween(300L, 3000L);
    }

    @Test
    void badTokensClose4401() throws Exception {
        for (String token : new String[] {"garbage",
                TestSupport.token(TestSupport.OTHER_KEY, "scarcity-auth", 60_000),
                TestSupport.token(TestSupport.KEY, "someone-else", 60_000),
                TestSupport.token(TestSupport.KEY, "scarcity-auth", -5_000)}) {
            WsClient c = WsClient.connect(port);
            c.auth(token);
            assertThat(c.awaitClose(5000)).as(token).isEqualTo(4401);
            assertThat(c.seen).isEmpty();
        }
        WsClient missing = WsClient.connect(port);
        missing.send("{\"type\":\"auth\"}");
        assertThat(missing.awaitClose(5000)).isEqualTo(4401);
    }

    @Test
    void goodTokenGetsReadyWithUserAndUsername() throws Exception {
        WsClient c = WsClient.connect(port);
        c.auth(TestSupport.validToken());
        JsonNode ready = c.next();
        assertThat(ready.path("type").asText()).isEqualTo("ready");
        assertThat(ready.path("user").asText()).isEqualTo("00000000-0000-4000-8000-0000000000aa");
        assertThat(ready.path("username").asText()).isEqualTo("tester");
        Thread.sleep(800); // longer than the 500 ms auth timeout: an authed socket stays open
        assertThat(c.closed.isDone()).isFalse();
    }

    @Test
    void tokenExpiringSoonClosesWith4401AfterItPasses() throws Exception {
        WsClient c = WsClient.connect(port);
        long start = System.currentTimeMillis();
        c.auth(TestSupport.token(TestSupport.KEY, "scarcity-auth", 2_000)); // JWT exp has 1 s resolution
        assertThat(c.next().path("type").asText()).isEqualTo("ready");
        assertThat(c.awaitClose(5000)).isEqualTo(4401);
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(900L);
    }

    // ---- protocol ----

    @Test
    void subscribeSendsSubscribedThenDigestMatchingWorld() throws Exception {
        String area = areaId(10);
        Map<String, Long> kids = new HashMap<>();
        kids.put(entityId(11), 4L);
        kids.put(entityId(12), 7L);
        WORLD.area(area, 3, kids);
        WsClient c = authed();
        String token = TestSupport.validToken();
        c.send("{\"type\":\"subscribe\",\"area\":\"" + area + "\"}");
        JsonNode subscribed = c.next();
        assertThat(subscribed.toString()).isEqualTo("{\"type\":\"subscribed\",\"area\":\"" + area + "\"}");
        JsonNode digest = c.next();
        assertThat(digest.path("type").asText()).isEqualTo("digest");
        assertThat(digest.path("area").asText()).isEqualTo(area);
        assertThat(digest.path("versions")).isEqualTo(JSON.readTree(
                "{\"" + area + "\":3,\"" + entityId(11) + "\":4,\"" + entityId(12) + "\":7}"));
        assertThat(WORLD.authHeaders).isNotEmpty().allMatch(h -> h.startsWith("Bearer ey"));
        assertThat(token).startsWith("ey");
    }

    @Test
    void childrenAreReadFromAllPages() throws Exception {
        String area = areaId(20);
        Map<String, Long> kids = new HashMap<>();
        for (int i = 0; i < 450; i++) {
            kids.put(entityId(1000 + i), (long) i + 1);
        }
        WORLD.area(area, 9, kids);
        WsClient c = authed();
        c.send("{\"type\":\"subscribe\",\"area\":\"" + area + "\"}");
        c.awaitType("subscribed");
        JsonNode versions = c.awaitType("digest").path("versions");
        assertThat(versions.size()).isEqualTo(451);
        assertThat(versions.path(entityId(1449)).asLong()).isEqualTo(450);
    }

    @Test
    void secondSocketSharesTheAreaWithoutCallingWorldAgain() throws Exception {
        String area = areaId(30);
        WORLD.area(area, 1, Map.of(entityId(31), 1L));
        WsClient a = authed();
        WsClient b = authed();
        a.send("{\"type\":\"subscribe\",\"area\":\"" + area + "\"}");
        a.awaitType("digest");
        int callsAfterFirst = WORLD.callsFor(area);
        assertThat(callsAfterFirst).isEqualTo(2); // the area + one page of children
        b.send("{\"type\":\"subscribe\",\"area\":\"" + area + "\"}");
        assertThat(b.next().path("type").asText()).isEqualTo("subscribed");
        assertThat(b.next().path("type").asText()).isEqualTo("digest");
        assertThat(WORLD.callsFor(area)).isEqualTo(callsAfterFirst);
    }

    @Test
    void subscribingTwiceIsIdempotent() throws Exception {
        String area = areaId(40);
        WORLD.area(area, 1, Map.of());
        WsClient c = authed();
        String sub = "{\"type\":\"subscribe\",\"area\":\"" + area + "\"}";
        c.send(sub);
        c.send(sub);
        for (int i = 0; i < 2; i++) {
            assertThat(c.next().path("type").asText()).isEqualTo("subscribed");
            assertThat(c.next().path("type").asText()).isEqualTo("digest");
        }
        publish(area, entityId(41), 2, 1.0);
        c.awaitType("changes");
        Thread.sleep(500);
        // One socket, subscribed twice: still exactly one changes frame.
        assertThat(c.seen.stream().filter(f -> "changes".equals(f.path("type").asText()))).hasSize(1);
    }

    @Test
    void unknownAreaIsAnErrorAndNotSubscribed() throws Exception {
        WsClient c = authed();
        c.send("{\"type\":\"subscribe\",\"area\":\"" + areaId(50) + "\"}");
        JsonNode e = c.next();
        assertThat(e.toString()).isEqualTo("{\"type\":\"error\",\"message\":\"unknown area\"}");
        assertThat(registry.hasArea(areaId(50))).isFalse();
        assertThat(c.closed.isDone()).isFalse();
    }

    @Test
    void malformedAreaIdsAreErrors() throws Exception {
        WsClient c = authed();
        for (String frame : new String[] {"{\"type\":\"subscribe\"}", "{\"type\":\"subscribe\",\"area\":\"nope\"}",
                "{\"type\":\"subscribe\",\"area\":42}", "{\"type\":\"subscribe\",\"area\":\"entity:abc\"}",
                "{\"type\":\"unsubscribe\",\"area\":\"\"}"}) {
            c.send(frame);
            assertThat(c.next().path("type").asText()).as(frame).isEqualTo("error");
        }
        assertThat(c.closed.isDone()).isFalse();
    }

    @Test
    void worldFailuresBecomeErrorsOrClose() throws Exception {
        WsClient c = authed();
        c.send("{\"type\":\"subscribe\",\"area\":\"" + areaId(TestSupport.StubWorld.BROKEN_AREA) + "\"}");
        JsonNode e = c.next();
        assertThat(e.path("type").asText()).isEqualTo("error");
        assertThat(e.path("message").asText()).contains("500");
        WsClient d = authed();
        d.send("{\"type\":\"subscribe\",\"area\":\"" + areaId(TestSupport.StubWorld.UNAUTHORIZED_AREA) + "\"}");
        assertThat(d.awaitClose(5000)).isEqualTo(4401);
    }

    @Test
    void batchesReachOnlyTheSocketsOfThatAreaAndUpdateTheDigest() throws Exception {
        String a1 = areaId(60);
        String a2 = areaId(61);
        WORLD.area(a1, 1, Map.of(entityId(62), 1L));
        WORLD.area(a2, 1, Map.of(entityId(63), 1L));
        WsClient s1 = authed();
        WsClient s2 = authed();
        WsClient other = authed();
        s1.send("{\"type\":\"subscribe\",\"area\":\"" + a1 + "\"}");
        s2.send("{\"type\":\"subscribe\",\"area\":\"" + a1 + "\"}");
        other.send("{\"type\":\"subscribe\",\"area\":\"" + a2 + "\"}");
        s1.awaitType("digest");
        s2.awaitType("digest");
        other.awaitType("digest");

        publish(a1, entityId(62), 2, 6.5);
        for (WsClient s : List.of(s1, s2)) {
            JsonNode ch = s.awaitType("changes");
            assertThat(ch.path("area").asText()).isEqualTo(a1);
            assertThat(ch.path("changes")).hasSize(1);
            JsonNode c0 = ch.path("changes").get(0);
            assertThat(c0.path("id").asText()).isEqualTo(entityId(62));
            assertThat(c0.path("type").asText()).isEqualTo("entity");
            assertThat(c0.path("version").asLong()).isEqualTo(2);
            assertThat(c0.path("patch").path("x").asDouble()).isEqualTo(6.5);
        }
        // The next periodic digest reflects the new version.
        JsonNode d = s1.await(f -> "digest".equals(f.path("type").asText())
                && f.path("versions").path(entityId(62)).asLong() == 2, 8000);
        assertThat(d.path("area").asText()).isEqualTo(a1);
        Thread.sleep(300);
        assertThat(other.seen.stream().filter(f -> "changes".equals(f.path("type").asText()))).isEmpty();
        // An older version never lowers the map.
        publish(a1, entityId(62), 1, 0.0);
        s1.awaitType("changes");
        JsonNode d2 = s1.await(f -> "digest".equals(f.path("type").asText()), 8000);
        assertThat(d2.path("versions").path(entityId(62)).asLong()).isEqualTo(2);
    }

    void publishChanges(String area, ChangeBatch.Change... changes) {
        tx.executeWithoutResult(s -> publisher.publish(new ChangeBatch(area, List.of(changes), null)));
    }

    /** Next digest frame in which the marker entity has version v. */
    JsonNode digestWith(WsClient s, String marker, long v) throws Exception {
        return s.await(f -> "digest".equals(f.path("type").asText())
                && f.path("versions").path(marker).asLong() == v, 8000);
    }

    @Test
    void createdResourceAppearsInTheNextDigest() throws Exception {
        String a = areaId(170);
        WORLD.area(a, 1, Map.of(entityId(171), 1L));
        WsClient s = authed();
        s.send("{\"type\":\"subscribe\",\"area\":\"" + a + "\"}");
        s.awaitType("digest");
        publishChanges(a, new ChangeBatch.Change(entityId(172), ResourceType.ENTITY, 1, JSON.createObjectNode().put("x", 1.0)));
        JsonNode d = digestWith(s, entityId(172), 1);
        assertThat(d.path("versions").path(entityId(171)).asLong()).isEqualTo(1);
    }

    @Test
    void deletedResourceLeavesTheDigestAndStaysGone() throws Exception {
        String a = areaId(173);
        WORLD.area(a, 1, Map.of(entityId(174), 1L, entityId(175), 1L));
        WsClient s = authed();
        s.send("{\"type\":\"subscribe\",\"area\":\"" + a + "\"}");
        s.awaitType("digest");
        publishChanges(a, ChangeBatch.Change.deleted(entityId(174), ResourceType.ENTITY));
        publishChanges(a, new ChangeBatch.Change(entityId(175), ResourceType.ENTITY, 2, JSON.createObjectNode().put("x", 2.0)));
        JsonNode d = digestWith(s, entityId(175), 2);
        assertThat(d.path("versions").has(entityId(174))).isFalse();
        // A later change batch for another resource does not bring it back.
        publishChanges(a, new ChangeBatch.Change(entityId(175), ResourceType.ENTITY, 3, JSON.createObjectNode().put("x", 3.0)));
        JsonNode d2 = digestWith(s, entityId(175), 3);
        assertThat(d2.path("versions").has(entityId(174))).isFalse();
    }

    @Test
    void childrenAndDeletedReachTheSocketAsPublishedAndPlainChangesHaveNeither() throws Exception {
        String a = areaId(176);
        WORLD.area(a, 1, Map.of(entityId(177), 1L));
        WsClient s = authed();
        s.send("{\"type\":\"subscribe\",\"area\":\"" + a + "\"}");
        s.awaitType("digest");
        publishChanges(a,
                ChangeBatch.Change.changed(a, ResourceType.AREA, 2, JSON.createObjectNode().put("n", 1), List.of(entityId(177), entityId(178))),
                ChangeBatch.Change.deleted(entityId(179), ResourceType.ENTITY));
        JsonNode first = s.awaitType("changes").path("changes");
        assertThat(first).hasSize(2);
        assertThat(first.get(0).path("children")).hasSize(2);
        assertThat(first.get(0).path("children").get(1).asText()).isEqualTo(entityId(178));
        assertThat(first.get(0).has("deleted")).isFalse();
        assertThat(first.get(1).path("deleted").asBoolean()).isTrue();
        assertThat(first.get(1).has("version")).isFalse();
        assertThat(first.get(1).has("patch")).isFalse();
        assertThat(first.get(1).has("children")).isFalse();
        publish(a, entityId(177), 2, 1.5);
        JsonNode plain = s.awaitType("changes").path("changes").get(0);
        assertThat(plain.has("children")).isFalse();
        assertThat(plain.has("deleted")).isFalse();
        assertThat(plain.path("version").asLong()).isEqualTo(2);
    }

    @Test
    void deletedChangeForAnUnknownResourceIsHarmless() throws Exception {
        String a = areaId(180);
        WORLD.area(a, 1, Map.of(entityId(181), 1L));
        WsClient s = authed();
        s.send("{\"type\":\"subscribe\",\"area\":\"" + a + "\"}");
        s.awaitType("digest");
        publishChanges(a, ChangeBatch.Change.deleted(entityId(182), ResourceType.ENTITY));
        publish(a, entityId(181), 2, 1.0);
        JsonNode d = digestWith(s, entityId(181), 2);
        assertThat(d.path("versions").has(entityId(182))).isFalse();
    }

    @Test
    void createThenDeleteInSuccessiveBatchesLeavesNoEntry() throws Exception {
        String a = areaId(183);
        WORLD.area(a, 1, Map.of(entityId(184), 1L));
        WsClient s = authed();
        s.send("{\"type\":\"subscribe\",\"area\":\"" + a + "\"}");
        s.awaitType("digest");
        publish(a, entityId(185), 1, 1.0);
        publishChanges(a, ChangeBatch.Change.deleted(entityId(185), ResourceType.ENTITY));
        publish(a, entityId(184), 2, 1.0);
        JsonNode d = digestWith(s, entityId(184), 2);
        assertThat(d.path("versions").has(entityId(185))).isFalse();
    }

    @Test
    void unsubscribeStopsChangesAndPingGetsPong() throws Exception {
        String area = areaId(70);
        WORLD.area(area, 1, Map.of(entityId(71), 1L));
        WsClient c = authed();
        c.send("{\"type\":\"subscribe\",\"area\":\"" + area + "\"}");
        c.awaitType("digest");
        c.send("{\"type\":\"unsubscribe\",\"area\":\"" + area + "\"}");
        assertThat(c.awaitType("unsubscribed").path("area").asText()).isEqualTo(area);
        publish(area, entityId(71), 2, 1.0);
        c.send("{\"type\":\"ping\"}");
        assertThat(c.awaitType("pong").toString()).isEqualTo("{\"type\":\"pong\"}");
        Thread.sleep(500);
        assertThat(c.seen.stream().filter(f -> "changes".equals(f.path("type").asText()))).isEmpty();
        assertThat(registry.hasArea(area)).isFalse();
    }

    @Test
    void unknownTypeAndMalformedFramesAreErrorsAfterAuth() throws Exception {
        WsClient c = authed();
        c.send("{\"type\":\"dance\"}");
        assertThat(c.next().toString()).isEqualTo("{\"type\":\"error\",\"message\":\"unknown type\"}");
        c.send("{\"nothing\":1}");
        assertThat(c.next().path("message").asText()).isEqualTo("unknown type");
        c.send("[1,2");
        assertThat(c.next().path("type").asText()).isEqualTo("error");
        c.send("{\"type\":\"auth\",\"token\":\"x\"}");
        assertThat(c.next().path("type").asText()).isEqualTo("error");
        c.send("{\"type\":\"ping\"}");
        assertThat(c.awaitType("pong")).isNotNull();
    }

    @Test
    void closingOneSocketLeavesTheOtherReceivingBatches() throws Exception {
        String area = areaId(80);
        WORLD.area(area, 1, Map.of(entityId(81), 1L));
        WsClient a = authed();
        WsClient b = authed();
        a.send("{\"type\":\"subscribe\",\"area\":\"" + area + "\"}");
        b.send("{\"type\":\"subscribe\",\"area\":\"" + area + "\"}");
        a.awaitType("digest");
        b.awaitType("digest");
        a.ws.sendClose(1000, "bye").get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(a.awaitClose(5000)).isEqualTo(1000);
        publish(area, entityId(81), 2, 2.0);
        assertThat(b.awaitType("changes").path("changes").get(0).path("version").asLong()).isEqualTo(2);
        publish(area, entityId(81), 3, 3.0);
        assertThat(b.await(f -> "changes".equals(f.path("type").asText())
                && f.path("changes").get(0).path("version").asLong() == 3, 5000)).isNotNull();
        // Last socket leaves: the area's map is dropped.
        b.ws.sendClose(1000, "bye").get(5, java.util.concurrent.TimeUnit.SECONDS);
        long deadline = System.currentTimeMillis() + 5000;
        while (registry.hasArea(area) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(registry.hasArea(area)).isFalse();
    }

    @Test
    void oversizedFrameClosesTheSocket() throws Exception {
        WsClient c = authed();
        c.send("{\"type\":\"ping\",\"pad\":\"" + "x".repeat(70_000) + "\"}");
        assertThat(c.awaitClose(5000)).isEqualTo(1009);
    }

    @Test
    void serverSendsWebSocketPings() throws Exception {
        WsClient c = authed();
        long deadline = System.currentTimeMillis() + 5000;
        while (c.pings.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(c.pings.get()).isPositive();
    }
}
