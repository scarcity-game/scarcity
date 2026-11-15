package com.pessimisticit.scarcity.orbital;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * orbital's socket to subscriptions (docs/websocket.md): {@code auth} with the service token,
 * {@code subscribe} to the area on {@code ready}, and every {@code turn} frame for the area
 * to {@link Turns}. When the socket closes or fails, it connects again after a wait that
 * doubles from {@code orbital.reconnect} to {@code orbital.reconnect-max}, and is reset once
 * a connection is subscribed again; a {@code 4401} (the token expired) also drops the token.
 * A socket that hears nothing (not even a digest) for {@code orbital.silence} is dropped.
 */
@Component
public class TurnSocket implements SmartLifecycle {

    /** subscriptions' close code for an auth that is missing, invalid, late or expired. */
    static final int UNAUTHORIZED = 4401;

    private static final Logger LOG = LoggerFactory.getLogger(TurnSocket.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ServiceToken token;
    private final Turns turns;
    private final OrbitalProperties props;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("orbital-socket").factory());

    private boolean running;
    /** Counts connections, so callbacks of an old socket are ignored. */
    private long generation;
    private WebSocket socket;
    private Duration backOff;
    private Instant subscribedSince;
    private ScheduledFuture<?> silence;

    public TurnSocket(ServiceToken token, Turns turns, OrbitalProperties props, Clock clock) {
        this.token = token;
        this.turns = turns;
        this.props = props;
        this.clock = clock;
        this.backOff = props.reconnect();
    }

    @Override
    public synchronized void start() {
        running = true;
        timer.execute(this::connect);
    }

    @Override
    public synchronized void stop() {
        running = false;
        generation++;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "stopping");
            socket = null;
        }
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
        long gen;
        String bearer;
        synchronized (this) {
            if (!running) {
                return;
            }
            gen = ++generation;
        }
        try {
            bearer = token.current();
        } catch (RuntimeException e) {
            LOG.warn("no service token: {}", e.getMessage());
            lost(gen, null);
            return;
        }
        LOG.info("connecting to {}", props.subscriptionsUrl());
        http.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(props.subscriptionsUrl(), new Listener(gen, bearer))
                .whenComplete((ws, failure) -> {
                    if (failure != null) {
                        LOG.warn("could not connect: {}", rootMessage(failure));
                        lost(gen, null);
                    }
                });
    }

    /** The socket of {@code gen} is gone: drop the token on a 4401, and connect again later. */
    private synchronized void lost(long gen, Integer code) {
        if (gen != generation || !running) {
            return;
        }
        if (code != null && code == UNAUTHORIZED) {
            token.renew();
        }
        if (silence != null) {
            silence.cancel(false);
        }
        socket = null;
        subscribedSince = null;
        Duration wait = backOff;
        backOff = backOff.multipliedBy(2).compareTo(props.reconnectMax()) < 0
                ? backOff.multipliedBy(2) : props.reconnectMax();
        LOG.info("reconnecting in {}", wait);
        timer.schedule(this::connect, wait.toNanos(), TimeUnit.NANOSECONDS);
    }

    private synchronized boolean current(long gen) {
        return gen == generation && running;
    }

    /** Heard from the server: the silence watchdog starts again. */
    private synchronized void heard(long gen, WebSocket ws) {
        if (silence != null) {
            silence.cancel(false);
        }
        silence = timer.schedule(() -> {
            if (current(gen)) {
                LOG.warn("nothing from subscriptions for {}; dropping the socket", props.silence());
                ws.abort();
                lost(gen, null);
            }
        }, props.silence().toNanos(), TimeUnit.NANOSECONDS);
    }

    private void frame(long gen, WebSocket ws, JsonNode frame) {
        switch (frame.path("type").asText()) {
            case "ready" -> ws.sendText(frame("subscribe", "area", props.area()), true);
            case "subscribed" -> subscribed(gen);
            case "turn" -> {
                if (props.area().equals(frame.path("area").asText())) {
                    turns.offer(new TurnFrame(frame.path("area").asText(), frame.path("entity").asText(),
                            frame.path("version").asLong(), frame.path("since").asDouble()));
                }
            }
            case "error" -> LOG.warn("subscriptions: {}", frame.path("message").asText());
            default -> {
                // digest, changes, pong: orbital reads state from world when its turn comes.
            }
        }
    }

    private synchronized void subscribed(long gen) {
        if (gen == generation) {
            subscribedSince = clock.instant();
            backOff = props.reconnect();
            LOG.info("subscribed to {}", props.area());
        }
    }

    /** {@code {"type": type, key: value}}, in that order. */
    private static String frame(String type, String key, String value) {
        Map<String, String> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put(key, value);
        return WorldJson.toJson(frame);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.toString();
    }

    /** One socket's callbacks. The JDK calls them one at a time. */
    private final class Listener implements WebSocket.Listener {

        private final long gen;
        private final String bearer;
        private final StringBuilder text = new StringBuilder();

        Listener(long gen, String bearer) {
            this.gen = gen;
            this.bearer = bearer;
        }

        @Override
        public void onOpen(WebSocket ws) {
            synchronized (TurnSocket.this) {
                if (!current(gen)) {
                    ws.abort();
                    return;
                }
                socket = ws;
            }
            heard(gen, ws);
            ws.sendText(frame("auth", "token", bearer), true);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last && current(gen)) {
                heard(gen, ws);
                String whole = text.toString();
                text.setLength(0);
                try {
                    frame(gen, ws, WorldJson.fromJson(whole, JsonNode.class));
                } catch (RuntimeException e) {
                    LOG.warn("unreadable frame from subscriptions: {}", e.toString());
                }
            } else if (last) {
                text.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer message) {
            heard(gen, ws);
            return WebSocket.Listener.super.onPing(ws, message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            LOG.info("socket closed: {} {}", code, reason);
            lost(gen, code);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOG.warn("socket failed: {}", rootMessage(error));
            lost(gen, null);
        }
    }
}
