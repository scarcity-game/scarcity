package com.pessimisticit.scarcity.resourcemodel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.core.JacksonException;

/**
 * {@code pg_notify(<the area's channel>, json)} through the caller's transaction ({@link Channels#area}), and,
 * when the batch changes who is up, a {@link TurnNotice} on {@link Channels#TURNS}. Postgres limits
 * a payload to 8000 bytes, so a batch over {@value #MAX_PAYLOAD_BYTES} UTF-8 bytes is split into
 * several notifications, numbered {@code part}/{@code parts}, each with the batch's area, logSeqs
 * and traceparent and its changes in order; an update that still does not fit alone is sent as a
 * stub, so a consumer {@code GET}s it. A reload is one notification.
 */
public class PostgresChangePublisher implements ChangePublisher {

    public static final int MAX_PAYLOAD_BYTES = 7900;

    /** Room for {@code "part":n,"parts":n} on every part, so a part sized without them still fits. */
    private static final int PART_KEYS_BYTES = 40;

    private static final Logger log = LoggerFactory.getLogger(PostgresChangePublisher.class);

    private final JdbcTemplate jdbc;
    private final TraceparentSource traceparentSource;

    public PostgresChangePublisher(JdbcTemplate jdbc, TraceparentSource traceparentSource) {
        this.jdbc = jdbc;
        this.traceparentSource = traceparentSource;
    }

    public PostgresChangePublisher(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Override
    public void publish(ChangeBatch batch) {
        ChangeBatch b = batch;
        if (b.traceparent() == null && traceparentSource != null) {
            b = b.withTraceparent(traceparentSource.current());
        }
        if (!b.reloading() && b.changes().isEmpty()) {
            return;
        }
        sendBatch(b);
        TurnNotice notice = TurnNotice.of(b);
        if (notice != null) {
            send(Channels.TURNS, json(notice));
        }
    }

    private void sendBatch(ChangeBatch b) {
        String channel = Channels.area(b.area());
        if (b.reloading()) {
            send(channel, json(b));
            return;
        }
        List<List<ChangeBatch.Change>> parts = split(b);
        if (parts.size() == 1) {
            send(channel, json(b.withChanges(parts.getFirst())));
            return;
        }
        for (int i = 0; i < parts.size(); i++) {
            send(channel, json(b.part(parts.get(i), i + 1, parts.size())));
        }
    }

    private static List<List<ChangeBatch.Change>> split(ChangeBatch b) {
        int limit = MAX_PAYLOAD_BYTES - PART_KEYS_BYTES;
        int base = bytes(b.part(List.of(), 1, 1));
        List<List<ChangeBatch.Change>> parts = new ArrayList<>();
        List<ChangeBatch.Change> current = new ArrayList<>();
        int used = base;
        for (ChangeBatch.Change c : b.changes()) {
            ChangeBatch.Change fitted = fit(c, limit - base);
            int cs = bytes(fitted) + 1; // + comma
            if (!current.isEmpty() && used + cs > limit) {
                parts.add(current);
                current = new ArrayList<>();
                used = base;
            }
            current.add(fitted);
            used += cs;
        }
        parts.add(current);
        return parts;
    }

    private static ChangeBatch.Change fit(ChangeBatch.Change c, int room) {
        if (bytes(c) <= room) {
            return c;
        }
        log.warn("change {} v{} too large for one notification; sent as a stub", c.id(), c.version());
        return c.stub();
    }

    private static int bytes(Object o) {
        return json(o).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String json(Object o) {
        try {
            return Json.MAPPER.writeValueAsString(o);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("batch not serialisable", e);
        }
    }

    private void send(String channel, String payload) {
        jdbc.query("SELECT pg_notify(?, ?)", rs -> {
        }, channel, payload);
    }
}
