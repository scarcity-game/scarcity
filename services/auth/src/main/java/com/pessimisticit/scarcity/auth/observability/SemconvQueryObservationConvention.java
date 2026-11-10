package com.pessimisticit.scarcity.auth.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import net.ttddyy.observation.tracing.QueryContext;
import net.ttddyy.observation.tracing.QueryObservationConvention;

/**
 * JDBC query observations (datasource-micrometer) with OpenTelemetry database semconv span
 * attributes, on top of the library's own ({@code jdbc.query[n]}, {@code jdbc.row-affected},
 * {@code jdbc.datasource.name}):
 * <ul>
 * <li>{@code db.system} and {@code db.system.name}: the old and the stable semconv key for the
 * database product (e.g. {@code postgresql}), from the JDBC URL; omitted when unknown.</li>
 * <li>{@code db.query.text}: the SQL with {@code ?} placeholders, the same text as
 * {@code jdbc.query[0]} (batches are joined with {@code "; "}). Bind values stay off.</li>
 * </ul>
 * Both are high-cardinality key-values, so the {@code jdbc_query_seconds} metric tags don't
 * change. The library picks this up as a {@link QueryObservationConvention} bean.
 */
public class SemconvQueryObservationConvention implements QueryObservationConvention {

    private final String dbSystem;

    /** @param dbSystem semconv database system name, or null if unknown */
    public SemconvQueryObservationConvention(String dbSystem) {
        this.dbSystem = dbSystem;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(QueryContext context) {
        List<KeyValue> kv = new ArrayList<>();
        if (dbSystem != null) {
            kv.add(KeyValue.of("db.system", dbSystem));
            kv.add(KeyValue.of("db.system.name", dbSystem));
        }
        if (!context.getQueries().isEmpty()) {
            kv.add(KeyValue.of("db.query.text", String.join("; ", context.getQueries())));
        }
        return QueryObservationConvention.super.getHighCardinalityKeyValues(context).and(kv);
    }

    /**
     * Maps a JDBC URL to a semconv {@code db.system} value. Only products whose name is the
     * same in the old and the stable semconv are mapped; anything else is null.
     */
    static String dbSystemFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        // Testcontainers' jdbc:tc:postgresql: URLs too.
        if (url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:tc:postgresql:")) {
            return "postgresql";
        }
        if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:tc:mysql:")) {
            return "mysql";
        }
        if (url.startsWith("jdbc:mariadb:") || url.startsWith("jdbc:tc:mariadb:")) {
            return "mariadb";
        }
        return null;
    }
}
