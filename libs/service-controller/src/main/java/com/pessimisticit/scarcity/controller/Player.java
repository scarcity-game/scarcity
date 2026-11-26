package com.pessimisticit.scarcity.controller;

import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.controller.Chooser.Pick;
import com.pessimisticit.scarcity.controller.World.Intent;
import com.pessimisticit.scarcity.controller.World.WorldStatus;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * Plays one turn: read the entity, and if it is still at the turn's version and idle, read its
 * area's entities, plan every choice it may take ({@link Chooser#eligible}), draw one of the
 * feasible ones by weight and post it as an intent with the turn's version as {@code ifVersion}
 * and the draws as {@code reported}. With nothing feasible, or when world refuses the intent
 * (422), the entity waits instead ({@link #fallback}) and chooses afresh when it is up again:
 * nothing is remembered between turns (#316). Every failure ends the turn with an
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
    private final String principal;
    private final double fallbackWait;
    private final Map<Outcome, Counter> counters;
    private final Set<String> warned;
    private volatile Instant lastAccepted;

    public Player(World world, Chooser chooser, ControllerProperties controllerProperties, Clock clock,
            MeterRegistry meters) {
        this.counters = new EnumMap<>(Outcome.class);
        this.warned = new HashSet<>();
        this.world = world;
        this.chooser = chooser;
        this.clock = clock;
        this.principal = controllerProperties.principal();
        this.fallbackWait = controllerProperties.fallbackWait();
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder(controllerProperties.principal() + ".intents")
                    .description("the controller's turns, by how they ended")
                    .tag("outcome", outcome.tag())
                    .register(meters));
        }
    }

    /** Plays {@code turn}. Never throws. */
    public Outcome turn(TurnFrame turn) {
        Outcome outcome = safely(turn);
        counters.get(outcome).increment();
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
        if (self.version() != turn.version() || !self.idle()) {
            LOG.debug("{} is at version {}, not the turn's {}; waiting for the next turn", self.id(),
                    self.version(), turn.version());
            return Outcome.STALE;
        }
        Choice.Situation situation = situation(turn, self);
        Map<Choice, Choice.Plan> plans = new LinkedHashMap<>();
        for (Choice choice : chooser.eligible(self.actions())) {
            plans.put(choice, choice.plan(situation));
        }
        Optional<Pick> pick = chooser.pick(self.actions(), choice -> plans.get(choice).feasible());
        if (pick.isEmpty()) {
            LOG.debug("{} has no feasible choice among its actions {}", self.id(), self.actions());
            return fallback(self, "no feasible choice", considered(plans, null));
        }
        return post(self, pick.get(), plans);
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
        return new Choice.Situation(turn, self, view, world);
    }

    private Outcome post(Entity self, Pick pick, Map<Choice, Choice.Plan> plans) {
        Choice.Plan plan = plans.get(pick.choice());
        try {
            world.post(new Intent(self.id(), plan.actionSpec(), self.version(), reported(pick, plans)));
        } catch (WorldStatus e) {
            if (e.status() == UNPROCESSABLE) {
                warnOnce(pick.choice(), e.error());
                return isWait(plan) ? Outcome.INVALID : fallback(self, "rejected: " + e.error(), null);
            }
            throw e;
        }
        LOG.debug("{} at version {}: {}", self.id(), self.version(), plan.actionSpec().type());
        return Outcome.OK;
    }

    private static boolean isWait(Choice.Plan plan) {
        return Wait.INSTANCE.name().equals(plan.actionSpec().type());
    }

    /**
     * No choice yields an intent world accepts: post a wait so the turn passes to the next entity
     * (turns are strictly ordered, so a controller that posts nothing stalls the whole area), and
     * the entity chooses afresh when it is up again. Not possible when the entity can't wait
     * ({@link Outcome#UNAVAILABLE}) or world rejects the wait too ({@link Outcome#INVALID}).
     * {@code considered} (null: none) is what the choices planned, reported with it.
     */
    private Outcome fallback(Entity self, String reason, ObjectNode considered) {
        if (!self.actions().contains(Wait.INSTANCE.name())) {
            return Outcome.UNAVAILABLE;
        }
        ActionSpec spec = Wait.INSTANCE.spec(new Wait.Params(fallbackWait));
        ObjectNode reported = WorldJson.toObject(new FallbackReported(new Fallback(reason, fallbackWait)));
        if (considered != null) {
            reported.set("considered", considered);
        }
        try {
            world.post(new Intent(self.id(), spec, self.version(), reported));
        } catch (WorldStatus e) {
            if (e.status() == UNPROCESSABLE) {
                LOG.warn("{}: even the fallback wait was rejected: {}", self.id(), e.error());
                return Outcome.INVALID;
            }
            throw e;
        }
        LOG.debug("{} at version {}: waiting {} instead ({})", self.id(), self.version(), fallbackWait, reason);
        return Outcome.FALLBACK;
    }

    /** {@code reported} of a fallback wait: why, and no categorical draw. */
    private record FallbackReported(Fallback fallback) {
    }

    private record Fallback(String reason, double duration) {
    }

    /**
     * {@code reported}: the categorical {@code draw}, and the feasible {@code choices} its weights
     * and value index; {@code plan}, the drawn choice's own draws; {@code considered}, what the
     * other choices planned this turn.
     */
    private static ObjectNode reported(Pick pick, Map<Choice, Choice.Plan> plans) {
        ObjectNode reported = WorldJson.toObject(new Reported(pick.draw(), pick.names()));
        Map<String, Draw> chosen = plans.get(pick.choice()).draws();
        if (!chosen.isEmpty()) {
            reported.set("plan", draws(chosen));
        }
        ObjectNode considered = considered(plans, pick.choice());
        if (considered != null) {
            reported.set("considered", considered);
        }
        return reported;
    }

    /**
     * What each choice but {@code drawn} planned this turn, by name: {@code infeasible} (why) when
     * it wasn't feasible, and {@code plan}, its draws, when it made any. A feasible choice with no
     * draws is left out; null when that leaves nothing.
     */
    private static ObjectNode considered(Map<Choice, Choice.Plan> plans, Choice drawn) {
        ObjectNode considered = WorldJson.toObject(Map.of());
        plans.forEach((choice, plan) -> {
            if (choice == drawn || plan.feasible() && plan.draws().isEmpty()) {
                return;
            }
            ObjectNode entry = considered.putObject(choice.name());
            if (!plan.feasible()) {
                entry.put("infeasible", plan.reason());
            }
            if (!plan.draws().isEmpty()) {
                entry.set("plan", draws(plan.draws()));
            }
        });
        return considered.isEmpty() ? null : considered;
    }

    /** One by one, in the order made: each Draw carries its own "dist" (a map's values would lose it). */
    private static ObjectNode draws(Map<String, Draw> draws) {
        ObjectNode node = WorldJson.toObject(Map.of());
        draws.forEach((name, draw) -> node.set(name, WorldJson.toObject(draw)));
        return node;
    }

    /** {@code reported}: the draw, and the choices its weights and value index. */
    private record Reported(Draw draw, List<String> choices) {
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
