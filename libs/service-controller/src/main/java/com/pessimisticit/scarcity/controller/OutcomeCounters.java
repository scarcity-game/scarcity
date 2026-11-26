package com.pessimisticit.scarcity.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;

/** {@code <principal>.intents{outcome}}: the turns of a controller, by how they ended. */
final class OutcomeCounters {

    private final Map<Outcome, Counter> counters;

    OutcomeCounters(String principal, MeterRegistry meters) {
        this.counters = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder(principal + ".intents")
                    .description("the controller's turns, by how they ended")
                    .tag("outcome", outcome.tag())
                    .register(meters));
        }
    }

    void count(Outcome outcome) {
        counters.get(outcome).increment();
    }
}
