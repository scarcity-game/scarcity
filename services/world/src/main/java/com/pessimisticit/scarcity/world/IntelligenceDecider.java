package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.engine.Decider;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.TurnAnswer;
import com.pessimisticit.scarcity.world.model.TurnRequest;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides intelligence services' turns inside an advance (docs/actions.md, "Game time"): asks the
 * service with the area as the advance has it, so the advance goes on to the next player's or npc's
 * turn. It gives up, and the advance stops with the turn up for the dispatcher to retry, when:
 * <ul>
 * <li>the service fails;</li>
 * <li>it failed within the last {@code timeout}, so a down service doesn't slow every advance;</li>
 * <li>or the advance has run longer than {@code advance-budget}, so no advance holds its area's
 * lock for long.</li>
 * </ul>
 */
class IntelligenceDecider {

    private static final Logger LOG = LoggerFactory.getLogger(IntelligenceDecider.class);

    private final IntelligenceProperties intelligenceProperties;
    private final IntelligenceClient intelligenceClient;
    private final GameFactions gameFactions;
    private final Clock clock;
    /** Per principal: when it last failed inside an advance. */
    private final Map<String, Instant> failedAt;

    IntelligenceDecider(IntelligenceProperties intelligenceProperties, IntelligenceClient intelligenceClient,
            GameFactions gameFactions, Clock clock) {
        this.intelligenceProperties = intelligenceProperties;
        this.gameFactions = gameFactions;
        this.intelligenceClient = intelligenceClient;
        this.clock = clock;
        this.failedAt = new ConcurrentHashMap<>();
    }

    /** A decider for one advance starting now; {@link Decider#NONE} when no service is registered. */
    Decider forAdvance() {
        if (intelligenceProperties.services().isEmpty()) {
            return Decider.NONE;
        }
        Instant deadline = clock.instant().plus(intelligenceProperties.advanceBudget());
        return new Decider() {
            @Override
            public boolean decides(Controller controller) {
                return controller instanceof Controller.Service s && intelligenceProperties.registered(s.principal());
            }

            @Override
            public Optional<Decision> decide(AreaView areaView, Turn turn) {
                return IntelligenceDecider.this.decide(areaView, turn, deadline);
            }
        };
    }

    private Optional<Decider.Decision> decide(AreaView areaView, Turn turn, Instant deadline) {
        String principal = ((Controller.Service) turn.controller()).principal();
        Instant now = clock.instant();
        Instant failed = failedAt.get(principal);
        if (now.isAfter(deadline) || failed != null && now.isBefore(failed.plus(intelligenceProperties.timeout()))) {
            return Optional.empty();
        }
        try {
            double range = intelligenceProperties.services().get(principal).range();
            Optional<TurnRequest> request = TurnContexts.of(areaView, turn.entity(), turn.since(), range,
                    gameFactions.of(areaView.areaId()));
            if (request.isEmpty()) {
                return Optional.empty();
            }
            TurnAnswer answer = intelligenceClient.turn(principal, request.get());
            failedAt.remove(principal);
            return Optional.of(new Decider.Decision(answer.actionSpec(), answer.reported()));
        } catch (RuntimeException e) {
            // The advance stops with the turn up; its commit writes the outbox row, retried from there.
            failedAt.put(principal, now);
            LOG.warn("{}'s turn of {} couldn't be decided in the advance, left to the dispatcher: {}", principal,
                    turn.entity(), e.getMessage());
            return Optional.empty();
        }
    }
}
