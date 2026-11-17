package com.pessimisticit.scarcity.controller;

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
            super(new Player(null, null, Fixtures.props("http://unused", "ws://unused", "k"), Clock.systemUTC(),
                    new SimpleMeterRegistry()), ObservationRegistry.NOOP,
                    Fixtures.props("http://unused", "ws://unused", "k"));
            this.offered = new CopyOnWriteArrayList<>();
        }

        @Override
        public synchronized void offer(TurnFrame turn) {
            offered.add(turn);
        }
    }

    private final StubWorld stubWorld;
    private final StubSubscriptions stubSubscriptions;
    private final RecordingTurns recordingTurns;
    private TurnSocket turnSocket;

    TurnSocketTest() throws Exception {
        this.stubSubscriptions = new StubSubscriptions();
        this.recordingTurns = new RecordingTurns();
        stubWorld = new StubWorld();
    }

    @AfterEach
    void stop() throws Exception {
        if (turnSocket != null) {
            turnSocket.stop();
        }
        recordingTurns.stop();
        stubSubscriptions.close();
        stubWorld.close();
    }

    private TurnSocket start(Duration silence) {
        ControllerProperties props = Fixtures.props(stubWorld.url(), stubSubscriptions.url(), Fixtures.KEY, Duration.ZERO, silence);
        turnSocket = new TurnSocket(new ServiceToken(org.springframework.web.client.RestClient.builder(), props,
                Clock.systemUTC()), recordingTurns, props, Clock.systemUTC());
        turnSocket.start();
        return turnSocket;
    }

    private TurnSocket start() {
        return start(Duration.ofSeconds(90));
    }

    @Test
    void authsWithTheServiceTokenThenSubscribesToTheArea() {
        start();
        eventually(() -> turnSocket.subscribedSince() != null);
        assertThat(stubSubscriptions.received).containsExactly(
                "{\"type\":\"auth\",\"token\":\"token-1\"}",
                "{\"type\":\"subscribe\",\"area\":\"" + StubWorld.AREA + "\"}");
    }

    @Test
    void turnFramesForTheAreaGoToTurns() {
        start();
        eventually(() -> turnSocket.subscribedSince() != null);
        stubSubscriptions.send("{\"type\":\"turn\",\"area\":\"area:other\",\"entity\":\"" + StubWorld.ORBITER
                + "\",\"version\":3,\"since\":1.0}");
        stubSubscriptions.send("{\"type\":\"changes\",\"area\":\"" + StubWorld.AREA + "\",\"changes\":[]}");
        stubSubscriptions.send("not json");
        stubSubscriptions.sendTurn(StubWorld.ORBITER, 3);
        eventually(() -> !recordingTurns.offered.isEmpty());
        assertThat(recordingTurns.offered).containsExactly(new TurnFrame(StubWorld.AREA, StubWorld.ORBITER, 3, 0.0));
    }

    @Test
    void aClosedSocketReconnectsWithTheSameToken() {
        start();
        eventually(() -> turnSocket.subscribedSince() != null);
        stubSubscriptions.closeAll(1001);
        eventually(() -> stubSubscriptions.connections() == 2 && turnSocket.subscribedSince() != null);
        stubSubscriptions.sendTurn(StubWorld.ORBITER, 4);
        eventually(() -> !recordingTurns.offered.isEmpty());
        assertThat(stubWorld.tokensIssued()).isEqualTo(1);
    }

    @Test
    void a4401FetchesANewTokenBeforeReconnecting() {
        stubSubscriptions.accept(token -> !token.equals("token-1"));
        start();
        eventually(() -> turnSocket.subscribedSince() != null);
        assertThat(stubSubscriptions.connections()).isEqualTo(2);
        assertThat(stubWorld.tokensIssued()).isEqualTo(2);
        assertThat(stubSubscriptions.received.getLast()).contains("subscribe");
        assertThat(stubSubscriptions.received).filteredOn(f -> f.contains("auth")).hasSize(2)
                .last().satisfies(f -> assertThat(f).contains("token-2"));
    }

    @Test
    void anErrorBeforeSubscribedSubscribesAgainAfterTheBackOffOnTheSameSocket() {
        stubSubscriptions.failSubscribes(2);
        start();
        eventually(() -> turnSocket.subscribedSince() != null);
        assertThat(stubSubscriptions.connections()).isEqualTo(1);
        assertThat(stubSubscriptions.received).filteredOn(f -> f.contains("subscribe")).hasSize(3);
        List<Long> at = stubSubscriptions.subscribeTimes;
        // reconnect is 50 ms, doubling: the first wait is 50 ms, the second 100 ms
        assertThat(Duration.ofNanos(at.get(1) - at.get(0))).isGreaterThanOrEqualTo(Duration.ofMillis(45));
        assertThat(Duration.ofNanos(at.get(2) - at.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(95));
    }

    @Test
    void anErrorAfterSubscribedDoesNotSubscribeAgain() throws InterruptedException {
        start();
        eventually(() -> turnSocket.subscribedSince() != null);
        stubSubscriptions.send("{\"type\":\"error\",\"message\":\"unknown type\"}");
        Thread.sleep(300);
        assertThat(stubSubscriptions.subscribeTimes).hasSize(1);
        assertThat(turnSocket.subscribedSince()).isNotNull();
    }

    @Test
    void aSilentSocketIsDroppedAndReconnected() {
        start(Duration.ofMillis(300));
        eventually(() -> stubSubscriptions.connections() >= 3);
    }

    @Test
    void stoppingClosesTheSocketAndNothingReconnects() throws InterruptedException {
        start();
        eventually(() -> turnSocket.subscribedSince() != null);
        turnSocket.stop();
        eventually(() -> stubSubscriptions.open().isEmpty());
        Thread.sleep(300);
        assertThat(stubSubscriptions.connections()).isEqualTo(1);
        assertThat(turnSocket.subscribedSince()).isNull();
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
