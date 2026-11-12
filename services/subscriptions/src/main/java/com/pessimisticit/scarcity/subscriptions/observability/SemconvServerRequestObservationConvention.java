package com.pessimisticit.scarcity.subscriptions.observability;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP server observations with OpenTelemetry semantic-convention span attributes.
 *
 * <p>Spring Framework 6.2 only ships {@link DefaultServerRequestObservationConvention}
 * (Micrometer names: {@code method}, {@code status}, {@code uri}, ...); an OTel one arrives
 * in Framework 7. The low-cardinality key-values (which become the
 * {@code http_server_requests_seconds} metric tags, and also span attributes) are kept
 * exactly as Spring makes them, so metrics don't change. The semconv keys are
 * <em>high</em>-cardinality key-values, which only reach spans:
 * {@code http.request.method}, {@code url.path}, {@code url.scheme}, {@code http.route}
 * (only when a handler pattern matched; see {@link #route}), {@code error.type} (exception class, or the status
 * code for a 5xx), {@code server.address}, {@code network.protocol.version},
 * {@code user_agent.original}. Micrometer's {@code http.url} is replaced by {@code url.path}.
 *
 * <p>Key-values are strings. The integer attributes ({@code http.response.status_code},
 * {@code server.port}) are set by {@link SemconvTypedSpanAttributesHandler}.
 *
 * <p>The span name is the semconv {@code "{METHOD} {route}"}, or just {@code "{METHOD}"}
 * without a matched route. The metric name stays {@link #getName()}.
 *
 * <p>Deliberately absent: {@code url.query} (may carry secrets) and {@code client.address}.
 */
public class SemconvServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    static final int MAX_USER_AGENT_LENGTH = 256;

    private static final String CATCH_ALL = "/**";

    public SemconvServerRequestObservationConvention(String name) {
        super(name);
    }

    @Override
    public String getContextualName(ServerRequestObservationContext context) {
        String method = HttpSemconv.spanMethod(context.getCarrier().getMethod());
        String route = route(context);
        return route == null ? method : method + " " + route;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        List<KeyValue> kv = new ArrayList<>();
        kv.add(KeyValue.of("http.request.method", HttpSemconv.method(request.getMethod())));
        if (request.getRequestURI() != null) {
            kv.add(KeyValue.of("url.path", request.getRequestURI()));
        }
        if (request.getScheme() != null) {
            kv.add(KeyValue.of("url.scheme", request.getScheme()));
        }
        String route = route(context);
        if (route != null) {
            kv.add(KeyValue.of("http.route", route));
        }
        String errorType = errorType(context);
        if (errorType != null) {
            kv.add(KeyValue.of("error.type", errorType));
        }
        if (request.getServerName() != null) {
            kv.add(KeyValue.of("server.address", request.getServerName()));
        }
        String protocolVersion = HttpSemconv.protocolVersion(request.getProtocol());
        if (protocolVersion != null) {
            kv.add(KeyValue.of("network.protocol.version", protocolVersion));
        }
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && !userAgent.isEmpty()) {
            kv.add(KeyValue.of("user_agent.original", userAgent.length() > MAX_USER_AGENT_LENGTH
                    ? userAgent.substring(0, MAX_USER_AGENT_LENGTH) : userAgent));
        }
        return KeyValues.of(kv);
    }

    /**
     * The matched handler pattern ({@code "/"} for the root), or null if none matched.
     * A 404 whose pattern is the catch-all {@code /**} fell through to Spring's static
     * resource handler and found nothing: that is no route either.
     */
    static String route(ServerRequestObservationContext context) {
        String pattern = context.getPathPattern();
        if (pattern == null) {
            return null;
        }
        if (CATCH_ALL.equals(pattern) && context.getResponse() != null && context.getResponse().getStatus() == 404) {
            return null;
        }
        return pattern.isEmpty() ? "/" : pattern;
    }

    /** Semconv: servers treat only 5xx (and exceptions) as errors, not 4xx. */
    static String errorType(ServerRequestObservationContext context) {
        if (context.getError() != null) {
            return context.getError().getClass().getName();
        }
        HttpServletResponse response = context.getResponse();
        if (response != null && response.getStatus() >= 500) {
            return Integer.toString(response.getStatus());
        }
        return null;
    }
}
