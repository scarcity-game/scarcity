package com.pessimisticit.scarcity.resourcemodel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * {@code pg_notify('resource_changes', json)} through the caller's transaction. Postgres limits
 * a payload to 8000 bytes, so a batch over {@value #MAX_PAYLOAD_BYTES} UTF-8 bytes is split into
 * several notifications (same area/traceparent, changes in order); a single change that still does
 * not fit is sent with its patch and children omitted, so a consumer {@code GET}s it.
 */
public class PostgresChangePublisher implements ChangePublisher {

    public static final String CHANNEL = "resource_changes";
    public static final int MAX_PAYLOAD_BYTES = 7900;

    private static final Logger log = LoggerFactory.getLogger(PostgresChangePublisher.class);

    private final JdbcTemplate jdbc;
    private final TraceparentSource traceparent;

    public PostgresChangePublisher(JdbcTemplate jdbc, TraceparentSource traceparent) {
        this.jdbc = jdbc;
        this.traceparent = traceparent;
    }

    public PostgresChangePublisher(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Override
    public void publish(ChangeBatch batch) {
        if (batch.changes().isEmpty()) {
            return;
        }
        ChangeBatch b = batch;
        if (b.traceparent() == null && traceparent != null) {
            b = b.withTraceparent(traceparent.current());
        }
        int base = size(b.area(), List.of(), b.logSeq(), b.traceparent());
        List<ChangeBatch.Change> current = new ArrayList<>();
        int used = base;
        for (ChangeBatch.Change c : b.changes()) {
            ChangeBatch.Change fitted = fit(b, c);
            int cs = json(fitted).getBytes(StandardCharsets.UTF_8).length + 1; // + comma
            if (!current.isEmpty() && used + cs > MAX_PAYLOAD_BYTES) {
                send(new ChangeBatch(b.area(), current, b.logSeq(), b.traceparent()));
                current = new ArrayList<>();
                used = base;
            }
            current.add(fitted);
            used += cs;
        }
        if (!current.isEmpty()) {
            send(new ChangeBatch(b.area(), current, b.logSeq(), b.traceparent()));
        }
    }

    private ChangeBatch.Change fit(ChangeBatch b, ChangeBatch.Change c) {
        if (size(b.area(), List.of(c), b.logSeq(), b.traceparent()) <= MAX_PAYLOAD_BYTES) {
            return c;
        }
        log.warn("change {} v{} too large for one notification; patch and children omitted", c.id(), c.version());
        return new ChangeBatch.Change(c.id(), c.type(), c.version(), null, null, c.deleted());
    }

    private static int size(String area, List<ChangeBatch.Change> changes, Long logSeq, String tp) {
        return json(new ChangeBatch(area, changes, logSeq, tp)).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String json(Object o) {
        try {
            return Json.MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("batch not serialisable", e);
        }
    }

    private void send(ChangeBatch batch) {
        jdbc.query("SELECT pg_notify(?, ?)", rs -> {
        }, CHANNEL, json(batch));
    }
}
