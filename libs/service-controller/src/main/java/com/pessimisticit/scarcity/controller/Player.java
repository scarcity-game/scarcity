package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.controller.World.Intent;
import com.pessimisticit.scarcity.controller.World.WorldStatus;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

/**
 * Plays one turn in socket mode: read the entity, and if it is idle and at the turn's version (or
 * ahead of it and up in the area, see {@link #playable}), read its area's entities, let the
 * {@link Planner} plan every choice it may take and draw
 * one of the feasible ones by weight, and post it as an intent with the entity's version as
 * {@code ifVersion} and the draws as {@code reported}. With nothing feasible, or when world
 * refuses the intent (422), the entity waits instead and chooses afresh when it is up again:
 * nothing is remembered between turns (#316). Every failure ends the turn with an
 * {@link Outcome}; nothing is thrown. One turn at a time ({@link Turns}).
 */
public class Player {

    private static final Logger LOG = LoggerFactory.getLogger(Player.class);
    /** Distinct rejection reasons remembered for "warn once"; forgotten all at once past this. */
    private static final int MAX_WARNED = 1000;
    private static final int UNPROCESSABLE = 422;

    private final World world;
    private final Planner planner;
    private final Clock clock;
    private final String principal;
    private final OutcomeCounters counters;
    private final Set<String> warned;
    private volatile Instant lastAccepted;

    public Player(World world, Chooser chooser, ControllerProperties controllerProperties, Clock clock,
            MeterRegistry meters) {
        this.warned = new HashSet<>();
        this.world = world;
        this.planner = new Planner(chooser, controllerProperties.fallbackWait());
        this.clock = clock;
        this.principal = controllerProperties.principal();
        this.counters = new OutcomeCounters(controllerProperties.principal(), meters);
    }

    /** Plays {@code turn}. Never throws. */
    public Outcome turn(TurnFrame turn) {
        Outcome outcome = safely(turn);
        counters.count(outcome);
        if (outcome == Outcome.OK || outcome == Outcome.FALLBACK) {
            lastAccepted = clock.instant();
        }
        return outcome;
    }

    /** When world last accepted an intent; empty before the first. */
    public Optional<Instant> lastAccepted() {
        return Optional.ofNullable(lastAccepted);
    }

    private Outcome safely(TurnFrame turn) {
        try {
            return play(turn);
        } catch (WorldStatus e) {
            return refused(turn.entity(), e);
        } catch (ServiceToken.Refused e) {
            LOG.error("{}: check {}'s API key", e.getMessage(), principal);
            return Outcome.DENIED;
        } catch (ResourceAccessException e) {
            LOG.warn("unreachable: {}", e.getMostSpecificCause().toString());
            return Outcome.UNREACHABLE;
        } catch (RuntimeException e) {
            LOG.error("turn failed", e);
            return Outcome.ERROR;
        }
    }

    private Outcome play(TurnFrame turn) {
        Entity self = world.entity(turn.entity());
        if (!self.idle() || !playable(turn, self)) {
            LOG.debug("{} is at version {}, not the turn's {}; waiting for the next turn", self.id(),
                    self.version(), turn.version());
            return Outcome.STALE;
        }
        Planner.Decision decision = planner.decide(situation(turn, self));
        return switch (decision.kind()) {
            case UNAVAILABLE -> Outcome.UNAVAILABLE;
            case FALLBACK -> postFallback(self, decision);
            case CHOSEN -> post(self, decision);
        };
    }

    /**
     * Whether the entity may play the turn: it is at the turn's version, or ahead of it and world's area
     * says it is the one up. An older frame (subscriptions' version map behind world, repeated with every
     * checksum) would otherwise stall the area for good; world decides, so a race is a 409.
     */
    private boolean playable(TurnFrame turn, Entity self) {
        if (self.version() == turn.version()) {
            return true;
        }
        if (self.version() < turn.version()) {
            return false;
        }
        Turn active = world.area(turn.area()).activeEntity();
        boolean up = active != null && self.id().equals(active.entity());
        if (up) {
            LOG.info("{} is at version {}, ahead of the turn's {}, and is up in {}: playing at {}", self.id(),
                    self.version(), turn.version(), turn.area(), self.version());
        }
        return up;
    }

    /**
     * The turn as the entity sees it: its area's entities and cells read once, the entity itself as just
     * read, each wall resolved against its cell.
     */
    private Choice.Situation situation(TurnFrame turn, Entity self) {
        Map<String, Entity> entities = new LinkedHashMap<>();
        world.entities(turn.area()).forEach(e -> entities.put(e.id(), e));
        entities.put(self.id(), self);
        Map<String, Cell> cells = new LinkedHashMap<>();
        world.cells(turn.area()).forEach(c -> cells.put(c.id(), c));
        // Planning reads only the entities and cells, so the area's own state isn't read.
        AreaView view = AreaView.of(turn.area(), new Area(turn.area(), turn.since()), entities, cells);
        return new Choice.Situation(turn, self, view);
    }

    private Outcome post(Entity self, Planner.Decision decision) {
        try {
            world.post(intent(self, decision));
        } catch (WorldStatus e) {
            if (e.status() == UNPROCESSABLE) {
                warnOnce(decision.choice(), e.error());
                return decision.isWait() ? Outcome.INVALID
                        : postFallback(self, planner.fallback(self, "rejected: " + e.error(), null));
            }
            throw e;
        }
        LOG.debug("{} at version {}: {}", self.id(), self.version(), decision.answer().actionSpec().type());
        return Outcome.OK;
    }

    /**
     * No choice yields an intent world accepts: post a wait so the turn passes to the next entity
     * (turns are strictly ordered, so a controller that posts nothing stalls the whole area). Not
     * possible when the entity can't wait ({@link Outcome#UNAVAILABLE}) or world rejects the wait
     * too ({@link Outcome#INVALID}).
     */
    private Outcome postFallback(Entity self, Planner.Decision decision) {
        if (decision.kind() == Planner.Kind.UNAVAILABLE) {
            return Outcome.UNAVAILABLE;
        }
        try {
            world.post(intent(self, decision));
        } catch (WorldStatus e) {
            if (e.status() == UNPROCESSABLE) {
                LOG.warn("{}: even the fallback wait was rejected: {}", self.id(), e.error());
                return Outcome.INVALID;
            }
            throw e;
        }
        LOG.debug("{} at version {}: waiting instead", self.id(), self.version());
        return Outcome.FALLBACK;
    }

    private static Intent intent(Entity self, Planner.Decision decision) {
        return new Intent(self.id(), decision.answer().actionSpec(), self.version(), decision.answer().reported());
    }

    private void warnOnce(Choice choice, String reason) {
        if (warned.size() >= MAX_WARNED) {
            warned.clear();
        }
        if (warned.add(choice.name() + ": " + reason)) {
            LOG.warn("{} rejected, waiting instead: {}", choice.name(), reason);
        }
    }

    private Outcome refused(String entity, WorldStatus e) {
        return switch (e.status()) {
            case 409 -> {
                LOG.debug("{} changed or is no longer up ({}); waiting for the next turn", entity, e.error());
                yield Outcome.CONFLICT;
            }
            case 401, 403 -> {
                LOG.error("world refused {} ({}): check {}'s controller", principal, e.getMessage(), entity);
                yield Outcome.DENIED;
            }
            case 404 -> {
                LOG.error("world has no {} ({})", entity, e.getMessage());
                yield Outcome.NOT_FOUND;
            }
            default -> {
                LOG.error("world answered {}", e.getMessage());
                yield Outcome.ERROR;
            }
        };
    }
}
