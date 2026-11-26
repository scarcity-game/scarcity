package com.pessimisticit.scarcity.resourcemodel;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LISTENs over its own connection, polled on a daemon thread: always on {@link Channels#TURNS}, and on each
 * area's channel ({@link Channels#area}) while that area is {@link #listen}ed to. The wanted channels are
 * counted under one lock; the poll thread brings the connection's LISTENs in line with them every
 * {@value #POLL_MS} ms, and on a reconnect issues them all again. A dropped connection is reopened with
 * exponential backoff (250 ms to 10 s). Notifications sent while disconnected are lost (NOTIFY is not
 * durable); consumers recover through the version checks.
 */
public class PostgresChangeSubscriber implements ChangeSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PostgresChangeSubscriber.class);
    /** application_name of the LISTEN connection, visible in pg_stat_activity. */
    public static final String APPLICATION_NAME = "resource-changes-listener";
    private static final int POLL_MS = 50;
    private static final long KEEPALIVE_NANOS = Duration.ofSeconds(10).toNanos();

    private final ListenConnectionFactory listenConnectionFactory;
    private final List<Consumer<ChangeBatch>> listeners;
    private final List<Consumer<TurnNotice>> turnListeners;
    /** Guards {@link #wanted}, {@link #active} and {@link #waiting}. */
    private final Object lock;
    /** Area channel to how many {@link #listen}s want it. */
    private final Map<String, Integer> wanted;
    /** Area channels LISTENed on the current connection. */
    private final Set<String> active;
    /** Futures of {@link #listen} calls whose channel isn't active yet. */
    private final Map<String, List<CompletableFuture<Void>>> waiting;
    private volatile boolean running;
    private volatile boolean listening;
    private volatile Thread thread;
    private volatile Connection connection;

    public PostgresChangeSubscriber(ListenConnectionFactory listenConnectionFactory) {
        this.listeners = new CopyOnWriteArrayList<>();
        this.turnListeners = new CopyOnWriteArrayList<>();
        this.lock = new Object();
        this.wanted = new HashMap<>();
        this.active = new HashSet<>();
        this.waiting = new HashMap<>();
        this.listenConnectionFactory = listenConnectionFactory;
    }

    @Override
    public void addListener(Consumer<ChangeBatch> listener) {
        listeners.add(listener);
    }

    @Override
    public void addTurnListener(Consumer<TurnNotice> listener) {
        turnListeners.add(listener);
    }

    @Override
    public CompletableFuture<Void> listen(String areaId) {
        String channel = Channels.area(areaId);
        synchronized (lock) {
            wanted.merge(channel, 1, Integer::sum);
            if (active.contains(channel)) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> f = new CompletableFuture<>();
            waiting.computeIfAbsent(channel, c -> new ArrayList<>()).add(f);
            return f;
        }
    }

    @Override
    public void unlisten(String areaId) {
        String channel = Channels.area(areaId);
        synchronized (lock) {
            wanted.computeIfPresent(channel, (c, n) -> n > 1 ? n - 1 : null);
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().daemon().name("resource-changes-listener").start(this::run);
    }

    @Override
    public synchronized void stop() {
        running = false;
        listening = false;
        Thread t = thread;
        thread = null;
        closeQuietly();
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** True while a connection is open and LISTEN has been issued. */
    @Override
    public boolean isListening() {
        return listening;
    }

    @Override
    public boolean awaitListening(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!listening && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return listening;
    }

    /** The area channels LISTENed on the current connection; for tests. */
    Set<String> activeChannels() {
        synchronized (lock) {
            return Set.copyOf(active);
        }
    }

    private void run() {
        long backoff = 250;
        while (running) {
            try {
                connect();
                backoff = 250;
                poll();
            } catch (Exception e) {
                if (running) {
                    log.warn("resource-changes listener connection lost ({}); retrying in {} ms", e.toString(),
                            backoff);
                }
            } finally {
                listening = false;
                synchronized (lock) {
                    active.clear();
                }
                closeQuietly();
            }
            if (!running) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 10_000);
        }
    }

    private void connect() throws SQLException {
        Connection c = listenConnectionFactory.open();
        connection = c;
        c.setAutoCommit(true);
        try {
            c.setClientInfo("ApplicationName", APPLICATION_NAME);
        } catch (java.sql.SQLClientInfoException e) {
            log.debug("could not set application_name", e);
        }
        execute(c, "LISTEN", Channels.TURNS);
        reconcile(c);
        listening = true;
        log.info("listening on {}", Channels.TURNS);
    }

    private void poll() throws SQLException {
        Connection c = connection;
        PGConnection pg = c.unwrap(PGConnection.class);
        long lastCheck = System.nanoTime();
        while (running) {
            reconcile(c);
            PGNotification[] ns = pg.getNotifications(POLL_MS);
            if (ns != null) {
                for (PGNotification n : ns) {
                    dispatch(n.getName(), n.getParameter());
                }
            }
            if (System.nanoTime() - lastCheck > KEEPALIVE_NANOS) {
                try (Statement s = c.createStatement()) {
                    s.execute("SELECT 1");
                }
                lastCheck = System.nanoTime();
            }
        }
    }

    /** LISTENs to each wanted channel not yet active and UNLISTENs each active one no longer wanted. */
    private void reconcile(Connection c) throws SQLException {
        Set<String> add = new HashSet<>();
        Set<String> drop = new HashSet<>();
        synchronized (lock) {
            for (String ch : wanted.keySet()) {
                if (!active.contains(ch)) {
                    add.add(ch);
                }
            }
            for (String ch : active) {
                if (!wanted.containsKey(ch)) {
                    drop.add(ch);
                }
            }
        }
        for (String ch : drop) {
            // Out of active before the UNLISTEN, so a listen() meanwhile waits for the next round's LISTEN.
            synchronized (lock) {
                if (wanted.containsKey(ch)) {
                    continue;
                }
                active.remove(ch);
            }
            execute(c, "UNLISTEN", ch);
        }
        for (String ch : add) {
            execute(c, "LISTEN", ch);
            activated(ch);
        }
    }

    /** {@code channel} is LISTENed: completes its waiting futures (or, if no longer wanted, the next round drops it). */
    private void activated(String channel) {
        List<CompletableFuture<Void>> done;
        synchronized (lock) {
            active.add(channel);
            done = waiting.remove(channel);
        }
        if (done != null) {
            done.forEach(f -> f.complete(null));
        }
    }

    private static void execute(Connection c, String verb, String channel) throws SQLException {
        if (!Channels.valid(channel)) {
            throw new IllegalArgumentException("not a channel of ours: " + channel);
        }
        try (Statement s = c.createStatement()) {
            s.execute(verb + " " + channel);
        }
    }

    private void dispatch(String channel, String payload) {
        if (Channels.TURNS.equals(channel)) {
            deliver(payload, TurnNotice.class, turnListeners);
        } else {
            deliver(payload, ChangeBatch.class, listeners);
        }
    }

    private static <T> void deliver(String payload, Class<T> type, List<Consumer<T>> to) {
        T value;
        try {
            value = Json.MAPPER.readValue(payload, type);
        } catch (Exception e) {
            log.warn("undecodable {} payload ignored: {}", type.getSimpleName(), e.toString());
            return;
        }
        for (Consumer<T> l : to) {
            try {
                l.accept(value);
            } catch (Throwable e) {
                log.warn("change listener threw", e);
            }
        }
    }

    private void closeQuietly() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // already dead
            }
        }
    }
}
