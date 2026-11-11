package com.pessimisticit.scarcity.resourcemodel;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LISTENs on {@value PostgresChangePublisher#CHANNEL} over its own connection, polled on a daemon
 * thread. A dropped connection is reopened with exponential backoff (250 ms to 10 s) and LISTEN
 * is reissued. Notifications sent while disconnected are lost (NOTIFY is not durable); consumers
 * recover through the version checks.
 */
public class PostgresChangeSubscriber implements ChangeSubscriber {

    private static final Logger log = LoggerFactory.getLogger(PostgresChangeSubscriber.class);
    /** application_name of the LISTEN connection, visible in pg_stat_activity. */
    public static final String APPLICATION_NAME = "resource-changes-listener";
    private static final int POLL_MS = 500;
    private static final long KEEPALIVE_NANOS = Duration.ofSeconds(10).toNanos();

    private final ListenConnectionFactory connections;
    private final List<Consumer<ChangeBatch>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile boolean listening;
    private volatile Thread thread;
    private volatile Connection connection;

    public PostgresChangeSubscriber(ListenConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public void addListener(Consumer<ChangeBatch> listener) {
        listeners.add(listener);
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
        Connection c = connections.open();
        connection = c;
        c.setAutoCommit(true);
        try {
            c.setClientInfo("ApplicationName", APPLICATION_NAME);
        } catch (java.sql.SQLClientInfoException e) {
            log.debug("could not set application_name", e);
        }
        try (Statement s = c.createStatement()) {
            s.execute("LISTEN " + PostgresChangePublisher.CHANNEL);
        }
        listening = true;
        log.info("listening on {}", PostgresChangePublisher.CHANNEL);
    }

    private void poll() throws SQLException {
        Connection c = connection;
        PGConnection pg = c.unwrap(PGConnection.class);
        long lastCheck = System.nanoTime();
        while (running) {
            PGNotification[] ns = pg.getNotifications(POLL_MS);
            if (ns != null) {
                for (PGNotification n : ns) {
                    if (PostgresChangePublisher.CHANNEL.equals(n.getName())) {
                        dispatch(n.getParameter());
                    }
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

    private void dispatch(String payload) {
        ChangeBatch batch;
        try {
            batch = Json.MAPPER.readValue(payload, ChangeBatch.class);
        } catch (Exception e) {
            log.warn("undecodable resource_changes payload ignored: {}", e.toString());
            return;
        }
        for (Consumer<ChangeBatch> l : listeners) {
            try {
                l.accept(batch);
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
