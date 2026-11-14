package com.pessimisticit.scarcity.world.model;

/**
 * Argument checks for the model's constructors. Every message starts with the
 * key it is about, so a bad document says which key is wrong.
 */
final class Check {

    private Check() {
    }

    static <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalArgumentException(key + ": required");
        }
        return value;
    }

    static String text(String value, String key) {
        if (required(value, key).isBlank()) {
            throw new IllegalArgumentException(key + ": must not be blank");
        }
        return value;
    }

    static double finite(double value, String key) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(key + ": must be a finite number, got " + value);
        }
        return value;
    }

    static double positive(double value, String key) {
        if (finite(value, key) <= 0.0) {
            throw new IllegalArgumentException(key + ": must be > 0, got " + value);
        }
        return value;
    }

    static double nonNegative(double value, String key) {
        if (finite(value, key) < 0.0) {
            throw new IllegalArgumentException(key + ": must be >= 0, got " + value);
        }
        return value;
    }

    static Double optionalPositive(Double value, String key) {
        return value == null ? null : positive(value, key);
    }

    static Double optionalNonNegative(Double value, String key) {
        return value == null ? null : nonNegative(value, key);
    }
}
