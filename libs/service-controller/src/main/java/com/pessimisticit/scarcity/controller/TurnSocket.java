package com.pessimisticit.scarcity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * the controller's socket to subscriptions (docs/websocket.md): {@code auth} with the service token,
 * {@code subscribe} to the area on {@code ready} (or to the turn feed, with no area), and every
 * {@code turn} frame for the area (for any area, with the feed) to {@link Turns}. When the socket closes or fails, it connects again after a {@link BackOff}
 * from {@code controller.reconnect} to {@code controller.reconnect-max}, reset once a connection is
 * subscribed again; an {@code error} frame before {@code subscribed} sends {@code subscribe}
 * again after the same back-off, on the same socket; a {@code 4401} (the token expired) also drops the token. A socket that
 * hears nothing (not even a ping) for {@code controller.silence} is dropped.
 */
@Component
public class TurnSocket implements SmartLifecycle {

    /** subscriptions' WebSocket close code (docs/websocket.md) for an auth that is missing, invalid, late or expired. */
    static final int UNAUTHORIZED = 4401;

    private static final Logger LOG = LoggerFactory.getLogger(TurnSocket.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ServiceToken serviceToken;
    private final Turns turns;
    private final ControllerProperties controllerProperties;
    private final Clock clock;
    private final BackOff backOff;
    private final HttpClient http;
    private final ScheduledExecutorService timer;

    private boolean running;
    /** The live connection; callbacks of an older one are ignored. */
    private Connection connection;
    private Instant subscribedSince;
    private ScheduledFuture<?> silence;

    public TurnSocket(ServiceToken serviceToken, Turns turns, ControllerProperties controllerProperties, Clock clock) {
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(controllerProperties.principal() + "-socket").factory());
        this.serviceToken = serviceToken;
        this.turns = turns;
        this.controllerProperties = controllerProperties;
        this.clock = clock;
        this.backOff = new BackOff(controllerProperties.reconnect(), controllerProperties.reconnectMax());
    }

    @Override
    public synchronized void start() {
        running = true;
        timer.execute(this::connect);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (connection != null && connection.ws != null) {
            connection.ws.sendClose(WebSocket.NORMAL_CLOSURE, "stopping");
        }
        connection = null;
        subscribedSince = null;
        timer.shutdownNow();
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    /** When the current socket was subscribed to the area; null while it isn't. */
    public synchronized Instant subscribedSince() {
        return subscribedSince;
    }

    private void connect() {
        Connection c;
        synchronized (this) {
            if (!running) {
                return;
            }
            c = new Connection();
            connection = c;
        }
        try {
            c.bearer = serviceToken.current();
        } catch (RuntimeException e) {
            LOG.warn("no service token: {}", e.getMessage());
            c.lost(null);
            return;
        }
        LOG.info("connecting to {}", controllerProperties.subscriptionsUrl());
        http.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(controllerProperties.subscriptionsUrl(), new SocketListener(c))
                .whenComplete((ws, failure) -> {
                    if (failure != null) {
                        LOG.warn("could not connect: {}", rootMessage(failure));
                        c.lost(null);
                    }
                });
    }

    private synchronized boolean live(Connection c) {
        return running && c == connection;
    }

    /** Heard from {@code c}'s socket: the silence watchdog starts again. */
    private synchronized void resetSilence(Connection c, WebSocket ws) {
        if (silence != null) {
            silence.cancel(false);
        }
        if (!live(c)) {
            return;
        }
        silence = timer.schedule(() -> {
            if (live(c)) {
                LOG.warn("nothing from subscriptions for {}; dropping the socket", controllerProperties.silence());
                ws.abort();
                c.lost(null);
            }
        }, controllerProperties.silence().toNanos(), TimeUnit.NANOSECONDS);
    }

    /** {@code subscribe} to the configured area, or to the turn feed when there is none. */
    private String subscribeFrame() {
        return controllerProperties.feed() ? Frames.subscribeTurns() : Frames.subscribe(controllerProperties.area());
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.toString();
    }

    /** One connection attempt and, once open, its socket. */
    private final class Connection implements SocketListener.Handler {

        private volatile String bearer;
        private volatile WebSocket ws;
        /** Guarded by the socket's monitor. */
        private boolean subscribed;
        private boolean retrying;

        @Override
        public void opened(WebSocket socket) {
            if (!live(this)) {
                socket.abort();
                return;
            }
            ws = socket;
            resetSilence(this, socket);
            socket.sendText(Frames.auth(bearer), true);
        }

        @Override
        public void heard(WebSocket socket) {
            resetSilence(this, socket);
        }

        @Override
        public void frame(WebSocket socket, JsonNode frame) {
            if (!live(this)) {
                return;
            }
            switch (frame.path("type").asText()) {
                case "ready" -> socket.sendText(subscribeFrame(), true);
                case "subscribed" -> {
                    if (Frames.subscribedTo(frame, controllerProperties.area())) {
                        subscribed();
                    }
                }
                case "turn" -> Frames.turn(frame, controllerProperties.area()).ifPresent(turns::offer);
                case "error" -> failed(socket, frame.path("message").asText());
                default -> {
                    // digest, changes, pong: the controller reads state from world when its turn comes.
                }
            }
        }

        /**
         * An {@code error} frame. The socket stays open, so before {@code subscribed} (world was
         * down when subscriptions loaded the area) subscribe is sent again after the back-off;
         * after it, the error is only logged.
         */
        private void failed(WebSocket socket, String message) {
            synchronized (TurnSocket.this) {
                if (subscribed || retrying) {
                    LOG.warn("subscriptions: {}", message);
                    return;
                }
                retrying = true;
                Duration wait = backOff.next();
                LOG.warn("subscriptions: {}; subscribing again in {}", message, wait);
                timer.schedule(() -> resubscribe(socket), wait.toNanos(), TimeUnit.NANOSECONDS);
            }
        }

        private void resubscribe(WebSocket socket) {
            synchronized (TurnSocket.this) {
                retrying = false;
                if (!live(this) || subscribed) {
                    return;
                }
            }
            socket.sendText(subscribeFrame(), true);
        }

        private void subscribed() {
            synchronized (TurnSocket.this) {
                if (live(this)) {
                    subscribed = true;
                    subscribedSince = clock.instant();
                    backOff.reset();
                    LOG.info("subscribed to {}", controllerProperties.feed() ? "the turn feed" : controllerProperties.area());
                }
            }
        }

        /**
         * The socket closed or failed: connect again after the back-off. On close code
         * {@link #UNAUTHORIZED} (4401, not HTTP 401: subscriptions' WebSocket close code for a
         * missing, invalid or expired token) the token is dropped first, so the next connect
         * fetches a new one.
         */
        @Override
        public void lost(Integer code) {
            synchronized (TurnSocket.this) {
                if (!live(this)) {
                    return;
                }
                if (code != null && code == UNAUTHORIZED) {
                    serviceToken.renew();
                }
                if (silence != null) {
                    silence.cancel(false);
                }
                connection = null;
                subscribedSince = null;
                Duration wait = backOff.next();
                LOG.info("reconnecting in {}", wait);
                timer.schedule(TurnSocket.this::connect, wait.toNanos(), TimeUnit.NANOSECONDS);
            }
        }
    }
}
