package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * A random draw with everything needed to check it (docs/actions.md,
 * "Behaviours" and "Effect types"). The JSON {@code dist} selects the record.
 * The log's {@code "for"} key is not part of the draw; the log adds it.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "dist")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Draw.Categorical.class, name = "categorical"),
    @JsonSubTypes.Type(value = Draw.Gaussian.class, name = "gaussian"),
    @JsonSubTypes.Type(value = Draw.Bernoulli.class, name = "bernoulli")
})
public sealed interface Draw permits Draw.Categorical, Draw.Gaussian, Draw.Bernoulli {

    /** {@code value}: the index of the first weight whose cumulative share is greater than {@code u}. */
    record Categorical(
            @JsonProperty(required = true) List<Double> weights,
            @JsonProperty(required = true) double u,
            @JsonProperty(required = true) int value) implements Draw {
        public Categorical {
            weights = List.copyOf(Check.required(weights, "weights"));
            weights.forEach(w -> Check.finite(w, "weights"));
            Check.finite(u, "u");
        }
    }

    /** A gaussian value; with {@code truncate}, only the accepted value is recorded. */
    record Gaussian(
            @JsonProperty(required = true) double mean,
            @JsonProperty(required = true) double sd,
            Truncate truncate,
            @JsonProperty(required = true) double value) implements Draw {
        public Gaussian {
            Check.finite(mean, "mean");
            Check.nonNegative(sd, "sd");
            Check.finite(value, "value");
        }
    }

    /** True when {@code u < p}. */
    record Bernoulli(
            @JsonProperty(required = true) double p,
            @JsonProperty(required = true) double u,
            @JsonProperty(required = true) boolean value) implements Draw {
        public Bernoulli {
            Check.finite(p, "p");
            Check.finite(u, "u");
        }
    }

    /** Bounds of a truncated gaussian: {@code min} inclusive, {@code max} exclusive. */
    record Truncate(
            @JsonProperty(required = true) double min,
            @JsonProperty(required = true) double max) {
        public Truncate {
            Check.finite(min, "min");
            Check.finite(max, "max");
            if (max <= min) {
                throw new IllegalArgumentException("max: must be > min (" + min + "), got " + max);
            }
        }
    }
}
