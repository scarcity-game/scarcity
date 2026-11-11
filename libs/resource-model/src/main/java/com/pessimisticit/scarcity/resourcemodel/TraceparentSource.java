package com.pessimisticit.scarcity.resourcemodel;

/** Supplies the W3C traceparent of the current span, or null. Keeps micrometer out of the core API. */
@FunctionalInterface
public interface TraceparentSource {
    String current();
}
