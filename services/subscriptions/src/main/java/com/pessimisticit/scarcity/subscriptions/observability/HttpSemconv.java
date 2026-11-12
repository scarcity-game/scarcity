package com.pessimisticit.scarcity.subscriptions.observability;

import java.net.URI;
import java.util.Set;

/** Small helpers shared by the HTTP semconv conventions. */
final class HttpSemconv {

    /** The methods semconv knows; anything else is reported as {@code _OTHER}. */
    private static final Set<String> KNOWN_METHODS = Set.of(
            "CONNECT", "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE");

    private HttpSemconv() {
    }

    /** Value of {@code http.request.method}. */
    static String method(String method) {
        return method != null && KNOWN_METHODS.contains(method) ? method : "_OTHER";
    }

    /** The method part of a span name: the method, or {@code HTTP} when it isn't a known one. */
    static String spanMethod(String method) {
        return method != null && KNOWN_METHODS.contains(method) ? method : "HTTP";
    }

    /** {@code "HTTP/1.1"} becomes {@code "1.1"}; null if it doesn't look like HTTP. */
    static String protocolVersion(String protocol) {
        if (protocol == null || !protocol.startsWith("HTTP/") || protocol.length() == 5) {
            return null;
        }
        return protocol.substring(5);
    }

    /** {@code url.full} without user info, query or fragment. */
    static String fullUrlWithoutQuery(URI uri) {
        StringBuilder sb = new StringBuilder();
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append("://");
        }
        if (uri.getHost() != null) {
            sb.append(uri.getHost());
        }
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        return sb.toString();
    }

    /** The URI's port, or the scheme default (80/443); -1 if neither is known. */
    static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }
}
