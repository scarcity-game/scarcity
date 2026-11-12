package com.pessimisticit.scarcity.world.observability;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * HTTP client observations (RestClient, RestTemplate) with OpenTelemetry semantic-convention
 * span attributes. Same approach as {@link SemconvServerRequestObservationConvention}: the
 * low-cardinality key-values (the {@code http_client_requests_seconds} tags) are Spring's,
 * unchanged; the semconv keys are high-cardinality, so they only reach spans:
 * {@code http.request.method}, {@code url.full} (no user info, query or fragment),
 * {@code server.address} and {@code error.type} (exception class, or the status code for a
 * 4xx/5xx; for clients both are errors). Spring's {@code http.url} is replaced by
 * {@code url.full}. The integers {@code http.response.status_code} and {@code server.port}
 * come from {@link SemconvTypedSpanAttributesHandler}.
 *
 * <p>Span name: {@code "{METHOD}"}, or {@code "{METHOD} {uri template path}"} (query part
 * dropped) when the request was made from a URI template.
 */
public class SemconvClientRequestObservationConvention extends DefaultClientRequestObservationConvention {

    public SemconvClientRequestObservationConvention(String name) {
        super(name);
    }

    @Override
    public String getContextualName(ClientRequestObservationContext context) {
        ClientHttpRequest request = context.getCarrier();
        String method = HttpSemconv.spanMethod(request == null ? null : request.getMethod().name());
        if (context.getUriTemplate() != null) {
            // Spring's uri tag: the template's path, but with any query template still on it.
            String template = uri(context).getValue();
            int query = template.indexOf('?');
            if (query >= 0) {
                template = template.substring(0, query);
            }
            if (!template.isEmpty() && !"none".equals(template)) {
                return method + " " + template;
            }
        }
        return method;
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ClientRequestObservationContext context) {
        List<KeyValue> kv = new ArrayList<>();
        ClientHttpRequest request = context.getCarrier();
        if (request != null) {
            kv.add(KeyValue.of("http.request.method", HttpSemconv.method(request.getMethod().name())));
            URI uri = request.getURI();
            kv.add(KeyValue.of("url.full", HttpSemconv.fullUrlWithoutQuery(uri)));
            if (uri.getHost() != null) {
                kv.add(KeyValue.of("server.address", uri.getHost()));
            }
        }
        String errorType = errorType(context);
        if (errorType != null) {
            kv.add(KeyValue.of("error.type", errorType));
        }
        return KeyValues.of(kv);
    }

    static Integer statusCode(ClientRequestObservationContext context) {
        ClientHttpResponse response = context.getResponse();
        if (response == null) {
            return null;
        }
        try {
            return response.getStatusCode().value();
        } catch (IOException e) {
            return null;
        }
    }

    static String errorType(ClientRequestObservationContext context) {
        if (context.getError() != null) {
            return context.getError().getClass().getName();
        }
        Integer status = statusCode(context);
        if (status != null && status >= 400) {
            return Integer.toString(status);
        }
        return null;
    }
}
