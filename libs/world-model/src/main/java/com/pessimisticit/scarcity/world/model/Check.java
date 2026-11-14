package com.pessimisticit.scarcity.world.model;

/**
 * Argument checks for the constructors of the model and of the rules
 * ({@code model.rules}). Every message starts with the
 * key it is about, so a bad document says which key is wrong.
 */
public final class Check {

    private Check() {
    }

    public static <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalArgumentException(key + ": required");
        }
        return value;
    }

    public static String text(String value, String key) {
        if (required(value, key).isBlank()) {
            throw new IllegalArgumentException(key + ": must not be blank");
        }
        return value;
    }

    public static double finite(double value, String key) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(key + ": must be a finite number, got " + value);
        }
        return value;
    }

    public static double positive(double value, String key) {
        if (finite(value, key) <= 0.0) {
            throw new IllegalArgumentException(key + ": must be > 0, got " + value);
        }
        return value;
    }

    public static double nonNegative(double value, String key) {
        if (finite(value, key) < 0.0) {
            throw new IllegalArgumentException(key + ": must be >= 0, got " + value);
        }
        return value;
    }

    public static double probability(double value, String key) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(key + ": must be in [0, 1], got " + value);
        }
        return value;
    }

    public static Double optionalPositive(Double value, String key) {
        return value == null ? null : positive(value, key);
    }

    public static Double optionalNonNegative(Double value, String key) {
        return value == null ? null : nonNegative(value, key);
    }
}
