package com.pessimisticit.scarcity.orbital;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.orbital.Chooser.Pick;
import com.pessimisticit.scarcity.orbital.World.Intent;
import com.pessimisticit.scarcity.orbital.World.WorldStatus;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.WorldJson;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * Plays one turn: read the entity, and if it is still at the turn's version and idle, draw
 * one of its {@code actions}, plan it and post it as an intent with the turn's version as
 * {@code ifVersion} and the draw as {@code reported}. Every failure ends the turn with an
 * {@link Outcome}; nothing is thrown. One turn at a time ({@link Turns}).
 */
@Component
public class Player {

    private static final Logger LOG = LoggerFactory.getLogger(Player.class);
    /** Distinct rejection reasons remembered for "warn once"; forgotten all at once past this. */
    private static final int MAX_WARNED = 1000;
    private static final int UNPROCESSABLE = 422;

    private final World world;
    private final Chooser chooser;
    private final Clock clock;
    private final Map<Outcome, Counter> counters;
    private final Set<String> warned;
    private volatile Instant lastAccepted;

    public Player(World world, Chooser chooser, Clock clock, MeterRegistry meters) {
        this.counters = new EnumMap<>(Outcome.class);
        this.warned = new HashSet<>();
        this.world = world;
        this.chooser = chooser;
        this.clock = clock;
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("orbital.intents")
                    .description("orbital's turns, by how they ended")
                    .tag("outcome", outcome.tag())
                    .register(meters));
        }
    }

    /** Plays {@code turn}. Never throws. */
    public Outcome turn(TurnFrame turn) {
        Outcome outcome = safely(turn);
        counters.get(outcome).increment();
        if (outcome == Outcome.OK) {
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
            LOG.error("{}: check ORBITAL_API_KEY", e.getMessage());
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
        if (self.version() != turn.version() || !self.state().idle()) {
            LOG.debug("{} is at version {}, not the turn's {}; waiting for the next turn", self.id(),
                    self.version(), turn.version());
            return Outcome.STALE;
        }
        Optional<Pick> pick = chooser.pick(self.state().actions());
        if (pick.isEmpty()) {
            LOG.warn("{} has no choice it may make now among its actions {}", self.id(), self.state().actions());
            return Outcome.UNAVAILABLE;
        }
        return post(self, pick.get());
    }

    private Outcome post(Entity self, Pick pick) {
        ActionSpec action;
        try {
            action = pick.choice().plan(self, world);
        } catch (Choice.Unavailable e) {
            skip(pick.choice(), e.getMessage());
            return Outcome.UNAVAILABLE;
        }
        try {
            world.post(new Intent(self.id(), action, self.version(), reported(pick)));
        } catch (WorldStatus e) {
            if (e.status() == UNPROCESSABLE) {
                skip(pick.choice(), e.error());
                return Outcome.INVALID;
            }
            throw e;
        }
        LOG.debug("{} at version {}: {}", self.id(), self.version(), action.type());
        return Outcome.OK;
    }

    private static ObjectNode reported(Pick pick) {
        return WorldJson.toObject(new Reported(pick.draw(), pick.names()));
    }

    /** {@code reported}: the draw, and the choices its weights and value index. */
    private record Reported(Draw draw, List<String> choices) {
    }

    private void skip(Choice choice, String reason) {
        chooser.skip(choice);
        if (warned.size() >= MAX_WARNED) {
            warned.clear();
        }
        if (warned.add(choice.name() + ": " + reason)) {
            LOG.warn("{} rejected, skipping it for a while: {}", choice.name(), reason);
        }
    }

    private static Outcome refused(String entity, WorldStatus e) {
        return switch (e.status()) {
            case 409 -> {
                LOG.debug("{} changed or is no longer up ({}); waiting for the next turn", entity, e.error());
                yield Outcome.CONFLICT;
            }
            case 401, 403 -> {
                LOG.error("world refused orbital ({}): check {}'s controller", e.getMessage(), entity);
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
