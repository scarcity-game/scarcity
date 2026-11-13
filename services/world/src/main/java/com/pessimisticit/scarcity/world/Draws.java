package com.pessimisticit.scarcity.world;

import java.util.IllegalFormatException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utility class for drawing random numbers with logging.
 */
public final class Draws {
    private Draws() {
        // Private constructor to prevent instantiation
    }

    /**
     * Draws a random number from a Gaussian distribution.
     *
     * @param ctx the effect context
     * @param forWhat a description of what this draw is for
     * @param mean the mean of the distribution
     * @param sd the standard deviation of the distribution
     * @return the drawn value
     * @throws IllegalArgumentException if sd is negative or not finite, or mean is not finite
     */
    public static double gaussian(EffectContext ctx, String forWhat, double mean, double sd) {
        if (sd < 0 || !Double.isFinite(sd) || !Double.isFinite(mean)) {
            throw new IllegalArgumentException("Invalid parameters for gaussian draw");
        }

        double value = mean + sd * ctx.random().nextGaussian();
        ObjectNode record = JsonNodeFactory.instance.objectNode();
        record.put("for", forWhat);
        record.put("dist", "gaussian");
        record.put("mean", mean);
        record.put("sd", sd);
        record.put("value", value);
        ctx.draw(record);
        return value;
    }

    /**
     * Draws a random number from a truncated Gaussian distribution using rejection sampling.
     *
     * @param ctx the effect context
     * @param forWhat a description of what this draw is for
     * @param mean the mean of the distribution
     * @param sd the standard deviation of the distribution
     * @param min the minimum value (inclusive)
     * @param max the maximum value (exclusive)
     * @return the drawn value
     * @throws IllegalArgumentException if sd is not finite or not > 0, or min >= max, or any argument is not finite
     * @throws IllegalStateException if no valid value is found after 10000 attempts
     */
    public static double truncatedGaussian(EffectContext ctx, String forWhat, double mean, double sd, double min, double max) {
        if (!Double.isFinite(sd) || sd <= 0 || min >= max || !Double.isFinite(mean) || !Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("Invalid parameters for truncated gaussian draw");
        }

        double value;
        int attempts = 0;
        do {
            value = mean + sd * ctx.random().nextGaussian();
            attempts++;
            if (attempts > 10000) {
                throw new IllegalStateException("truncated gaussian: no value in range");
            }
        } while (value < min || value >= max);

        ObjectNode record = JsonNodeFactory.instance.objectNode();
        record.put("for", forWhat);
        record.put("dist", "gaussian");
        record.put("mean", mean);
        record.put("sd", sd);
        ObjectNode truncate = record.putObject("truncate");
        truncate.put("min", min);
        truncate.put("max", max);
        record.put("value", value);
        ctx.draw(record);
        return value;
    }

    /**
     * Draws a random boolean from a Bernoulli distribution.
     *
     * @param ctx the effect context
     * @param forWhat a description of what this draw is for
     * @param p the probability of true (between 0 and 1)
     * @return the drawn boolean value
     * @throws IllegalArgumentException if p is not in [0,1] or is NaN
     */
    public static boolean bernoulli(EffectContext ctx, String forWhat, double p) {
        if (p < 0 || p > 1 || Double.isNaN(p)) {
            throw new IllegalArgumentException("Invalid probability for bernoulli draw");
        }

        double u = ctx.random().nextDouble();
        boolean value = u < p;
        ObjectNode record = JsonNodeFactory.instance.objectNode();
        record.put("for", forWhat);
        record.put("dist", "bernoulli");
        record.put("p", p);
        record.put("u", u);
        record.put("value", value);
        ctx.draw(record);
        return value;
    }
}