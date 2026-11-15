package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TurnSocketTest {

    /** Records the turns it is offered. */
    private static final class RecordingTurns extends Turns {
        final List<TurnFrame> offered;

        RecordingTurns() {
            super(new Player(null, null, Clock.systemUTC(), new SimpleMeterRegistry()), ObservationRegistry.NOOP,
                    Fixtures.props("http://unused", "ws://unused", "k"));
            this.offered = new CopyOnWriteArrayList<>();
        }

        @Override
        public synchronized void offer(TurnFrame turn) {
            offered.add(turn);
        }
    }

    private final StubWorld auth;
    private final StubSubscriptions subscriptions;
    private final RecordingTurns turns;
    private TurnSocket socket;

    TurnSocketTest() throws Exception {
        this.subscriptions = new StubSubscriptions();
        this.turns = new RecordingTurns();
        auth = new StubWorld();
    }

    @AfterEach
    void stop() throws Exception {
        if (socket != null) {
            socket.stop();
        }
        turns.stop();
        subscriptions.close();
        auth.close();
    }

    private TurnSocket start(Duration silence) {
        OrbitalProperties props = Fixtures.props(auth.url(), subscriptions.url(), Fixtures.KEY, Duration.ZERO, silence);
        socket = new TurnSocket(new ServiceToken(org.springframework.web.client.RestClient.builder(), props,
                Clock.systemUTC()), turns, props, Clock.systemUTC());
        socket.start();
        return socket;
    }

    private TurnSocket start() {
        return start(Duration.ofSeconds(90));
    }

    @Test
    void authsWithTheServiceTokenThenSubscribesToTheArea() {
        start();
        eventually(() -> socket.subscribedSince() != null);
        assertThat(subscriptions.received).containsExactly(
                "{\"type\":\"auth\",\"token\":\"token-1\"}",
                "{\"type\":\"subscribe\",\"area\":\"" + StubWorld.AREA + "\"}");
    }

    @Test
    void turnFramesForTheAreaGoToTurns() {
        start();
        eventually(() -> socket.subscribedSince() != null);
        subscriptions.send("{\"type\":\"turn\",\"area\":\"area:other\",\"entity\":\"" + StubWorld.ORBITER
                + "\",\"version\":3,\"since\":1.0}");
        subscriptions.send("{\"type\":\"changes\",\"area\":\"" + StubWorld.AREA + "\",\"changes\":[]}");
        subscriptions.send("not json");
        subscriptions.sendTurn(StubWorld.ORBITER, 3);
        eventually(() -> !turns.offered.isEmpty());
        assertThat(turns.offered).containsExactly(new TurnFrame(StubWorld.AREA, StubWorld.ORBITER, 3, 0.0));
    }

    @Test
    void aClosedSocketReconnectsWithTheSameToken() {
        start();
        eventually(() -> socket.subscribedSince() != null);
        subscriptions.closeAll(1001);
        eventually(() -> subscriptions.connections() == 2 && socket.subscribedSince() != null);
        subscriptions.sendTurn(StubWorld.ORBITER, 4);
        eventually(() -> !turns.offered.isEmpty());
        assertThat(auth.tokensIssued()).isEqualTo(1);
    }

    @Test
    void a4401FetchesANewTokenBeforeReconnecting() {
        subscriptions.accept(token -> !token.equals("token-1"));
        start();
        eventually(() -> socket.subscribedSince() != null);
        assertThat(subscriptions.connections()).isEqualTo(2);
        assertThat(auth.tokensIssued()).isEqualTo(2);
        assertThat(subscriptions.received.getLast()).contains("subscribe");
        assertThat(subscriptions.received).filteredOn(f -> f.contains("auth")).hasSize(2)
                .last().satisfies(f -> assertThat(f).contains("token-2"));
    }

    @Test
    void anErrorBeforeSubscribedSubscribesAgainAfterTheBackOffOnTheSameSocket() {
        subscriptions.failSubscribes(2);
        start();
        eventually(() -> socket.subscribedSince() != null);
        assertThat(subscriptions.connections()).isEqualTo(1);
        assertThat(subscriptions.received).filteredOn(f -> f.contains("subscribe")).hasSize(3);
        List<Long> at = subscriptions.subscribeTimes;
        // reconnect is 50 ms, doubling: the first wait is 50 ms, the second 100 ms
        assertThat(Duration.ofNanos(at.get(1) - at.get(0))).isGreaterThanOrEqualTo(Duration.ofMillis(45));
        assertThat(Duration.ofNanos(at.get(2) - at.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(95));
    }

    @Test
    void anErrorAfterSubscribedDoesNotSubscribeAgain() throws InterruptedException {
        start();
        eventually(() -> socket.subscribedSince() != null);
        subscriptions.send("{\"type\":\"error\",\"message\":\"unknown type\"}");
        Thread.sleep(300);
        assertThat(subscriptions.subscribeTimes).hasSize(1);
        assertThat(socket.subscribedSince()).isNotNull();
    }

    @Test
    void aSilentSocketIsDroppedAndReconnected() {
        start(Duration.ofMillis(300));
        eventually(() -> subscriptions.connections() >= 3);
    }

    @Test
    void stoppingClosesTheSocketAndNothingReconnects() throws InterruptedException {
        start();
        eventually(() -> socket.subscribedSince() != null);
        socket.stop();
        eventually(() -> subscriptions.open().isEmpty());
        Thread.sleep(300);
        assertThat(subscriptions.connections()).isEqualTo(1);
        assertThat(socket.subscribedSince()).isNull();
    }

    private static void eventually(BooleanSupplier done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(done.getAsBoolean()).as("eventually").isTrue();
    }
}
