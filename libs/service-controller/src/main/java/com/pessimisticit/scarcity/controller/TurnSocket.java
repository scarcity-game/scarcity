package com.pessimisticit.scarcity.controller;

import tools.jackson.databind.JsonNode;
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
 * again after the same back-off, on the same socket (on the turn feed also after it); a {@code 4401} (the token expired) also drops the token. A socket that
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
    /** A replacement being opened with the renewed token, while {@link #connection} stays up. */
    private Connection pending;
    private Instant subscribedSince;
    private ScheduledFuture<?> rotation;

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
        if (pending != null && pending.ws != null) {
            pending.ws.abort();
        }
        connection = null;
        pending = null;
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
        open(c, null);
    }

    /**
     * The token the socket authenticated with ends at {@code exp}, and subscriptions closes
     * {@code 4401} then; there is no in-band re-auth (a second {@code auth} is an error,
     * docs/websocket.md). So when the token is due for renewal (two thirds of its life) a second
     * socket is opened with the new one, and the old one is closed once the new one is subscribed:
     * no gap, and turns heard on both are offered once ({@link Turns}).
     */
    private void rotate(Connection old) {
        Connection c;
        synchronized (this) {
            if (!live(old) || old != connection || pending != null) {
                return;
            }
            c = new Connection();
            pending = c;
        }
        LOG.info("renewing the socket's token: opening a second socket");
        open(c, old.bearer);
    }

    /** Connects {@code c} with a token other than {@code notThis} (the one a socket being replaced has). */
    private void open(Connection c, String notThis) {
        try {
            c.bearer = serviceToken.fresh(notThis);
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
        return running && (c == connection || c == pending);
    }

    /** Opens a replacement when {@code c}'s token is due for renewal. */
    private void scheduleRotation(Connection c) {
        if (rotation != null) {
            rotation.cancel(false);
        }
        long nanos = Math.max(0, Duration.between(clock.instant(), serviceToken.renewAt()).toNanos());
        rotation = timer.schedule(() -> rotate(c), nanos, TimeUnit.NANOSECONDS);
    }

    /** Heard from {@code c}'s socket: the silence watchdog starts again. */
    private synchronized void resetSilence(Connection c, WebSocket ws) {
        if (c.silence != null) {
            c.silence.cancel(false);
        }
        if (!live(c)) {
            return;
        }
        c.silence = timer.schedule(() -> {
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
        private ScheduledFuture<?> silence;

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
                    // checksum, changes, reload, pong: the controller reads state from world when its turn comes.
                }
            }
        }

        /**
         * An {@code error} frame. The socket stays open, so before {@code subscribed} (world was
         * down when subscriptions loaded the area) subscribe is sent again after the back-off. On the
         * turn feed the same holds after {@code subscribed}: subscriptions acknowledges, then lists
         * the areas, and an error then means it has none for us; the subscription is treated as
         * lost (health goes down until the next ack) and subscribe is sent again (subscriptions
         * joins an area once, so a repeat does not double it). For one area an error after
         * {@code subscribed} is only logged.
         */
        private void failed(WebSocket socket, String message) {
            synchronized (TurnSocket.this) {
                if (retrying || (subscribed && !controllerProperties.feed())) {
                    LOG.warn("subscriptions: {}", message);
                    return;
                }
                subscribed = false;
                subscribedSince = null;
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
                    replaceOld();
                    scheduleRotation(this);
                    LOG.info("subscribed to {}", controllerProperties.feed() ? "the turn feed" : controllerProperties.area());
                }
            }
        }

        /** The replacement failed to open: the live socket stays, and a new try is made after the back-off. */
        private void retryReplacement() {
            pending = null;
            Duration wait = backOff.next();
            LOG.warn("could not open the replacement socket, trying again in {}", wait);
            Connection old = connection;
            timer.schedule(() -> rotate(old), wait.toNanos(), TimeUnit.NANOSECONDS);
        }

        /** This connection was opened to replace the live one, which is closed now it is subscribed. */
        private void replaceOld() {
            if (pending != this) {
                return;
            }
            Connection old = connection;
            connection = this;
            pending = null;
            if (old != null) {
                if (old.silence != null) {
                    old.silence.cancel(false);
                }
                if (old.ws != null) {
                    old.ws.sendClose(WebSocket.NORMAL_CLOSURE, "token renewed");
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
                if (silence != null) {
                    silence.cancel(false);
                }
                if (pending == this) {
                    retryReplacement();
                    return;
                }
                if (code != null && code == UNAUTHORIZED) {
                    serviceToken.renew();
                }
                if (rotation != null) {
                    rotation.cancel(false);
                }
                if (pending != null) {
                    connection = pending;
                    pending = null;
                    subscribedSince = null;
                    return;
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
