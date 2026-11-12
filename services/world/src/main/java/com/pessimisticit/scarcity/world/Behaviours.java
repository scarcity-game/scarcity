package com.pessimisticit.scarcity.world;

import java.util.List;

/**
 * Categorical draw: the first index whose cumulative weight share exceeds {@code u}.
 *
 * <p>The method assumes all weights are positive finite numbers, the list is non‑empty,
 * and {@code u} is in the interval {@code [0,1)}.
 */
final class Behaviours {
    private Behaviours() {}

    static int pick(List<Double> weights, double u) {
        double total = 0.0;
        for (double w : weights) {
            total += w;
        }
        double cum = 0.0;
        for (int i = 0; i < weights.size(); i++) {
            cum += weights.get(i);
            if (cum / total > u) {
                return i;
            }
        }
        return weights.size() - 1;
    }
}
