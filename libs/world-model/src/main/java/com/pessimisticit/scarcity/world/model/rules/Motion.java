package com.pessimisticit.scarcity.world.model.rules;

/**
 * Where a channel is heading, as a velocity: the entity's position now moves by
 * ({@code vx}, {@code vy}) per unit of game time until the action ends. The engine forecasts
 * contacts from it ({@link Contacts}).
 */
public record Motion(double vx, double vy) {

    /** Standing still. */
    public static final Motion STILL = new Motion(0.0, 0.0);
}
