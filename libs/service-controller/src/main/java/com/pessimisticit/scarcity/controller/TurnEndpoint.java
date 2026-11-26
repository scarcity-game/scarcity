package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Cell;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.TurnAnswer;
import com.pessimisticit.scarcity.world.model.TurnRequest;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.WorldJsonException;
import com.pessimisticit.scarcity.world.model.rules.AreaView;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /turn} of an intelligence service (docs/actions.md, "Intelligence services"): world
 * sends the turn with everything the service may know ({@link TurnRequest}), and the answer is the
 * intent's body ({@link TurnAnswer}). The service keeps no state and calls nobody: the
 * {@link Planner} plans from the request alone.
 *
 * <p>Statuses: {@code 200} the answer; {@code 400} the body isn't a turn request; {@code 401} no
 * valid token; {@code 403} a valid token that isn't world's; {@code 422} no choice is feasible and the
 * entity can't wait (world retries); {@code 503} auth's keys can't be reached (world retries).
 * There is no stale check, world applies the answer with {@code ifVersion}. Each turn is one
 * {@code <principal>.turn} observation (a span and a timer) and counts in
 * {@code <principal>.intents{outcome}}.
 */
@RestController
class TurnEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(TurnEndpoint.class);

    private final Planner planner;
    private final WorldCaller worldCaller;
    private final ObservationRegistry observations;
    private final OutcomeCounters counters;
    private final String turnName;

    TurnEndpoint(Planner planner, WorldCaller worldCaller, ObservationRegistry observations,
            OutcomeCounters counters, CommonProperties commonProperties) {
        this.planner = planner;
        this.worldCaller = worldCaller;
        this.observations = observations;
        this.counters = counters;
        this.turnName = commonProperties.principal() + ".turn";
    }

    @PostMapping(path = "/turn", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> turn(@RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody String body) {
        ResponseEntity<String> refusal = refusal(worldCaller.check(authorization));
        if (refusal != null) {
            counters.count(Outcome.DENIED);
            return refusal;
        }
        Observation observation = Observation.createNotStarted(turnName, observations);
        return observation.observe(() -> {
            Played played = play(body);
            counters.count(played.outcome());
            observation.lowCardinalityKeyValue("outcome", played.outcome().tag());
            return played.response();
        });
    }

    private static ResponseEntity<String> refusal(WorldCaller.Verdict verdict) {
        return switch (verdict) {
            case ALLOWED -> null;
            case UNAUTHENTICATED -> error(HttpStatus.UNAUTHORIZED, "a valid token is required");
            case FORBIDDEN -> error(HttpStatus.FORBIDDEN, "only world may ask for a turn");
            case UNVERIFIABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "the token can't be verified now");
        };
    }

    /** How a turn ended, and what to answer. */
    private record Played(Outcome outcome, ResponseEntity<String> response) {
    }

    /** Never throws: a turn that fails is a 4xx or 5xx. */
    private Played play(String body) {
        try {
            TurnRequest request = WorldJson.fromJson(body, TurnRequest.class);
            Planner.Decision decision = planner.decide(situation(request));
            if (decision.kind() == Planner.Kind.UNAVAILABLE) {
                LOG.warn("{}: no feasible choice and it can't wait; answering 422", request.entity());
                return new Played(Outcome.UNAVAILABLE,
                        error(HttpStatus.UNPROCESSABLE_CONTENT, "no feasible choice, and the entity can't wait"));
            }
            Outcome outcome = decision.kind() == Planner.Kind.FALLBACK ? Outcome.FALLBACK : Outcome.OK;
            return new Played(outcome, ResponseEntity.ok(WorldJson.toJson(decision.answer())));
        } catch (WorldJsonException | IllegalArgumentException e) {
            return new Played(Outcome.INVALID, error(HttpStatus.BAD_REQUEST, e.getMessage()));
        } catch (RuntimeException e) {
            LOG.error("turn failed", e);
            return new Played(Outcome.ERROR, error(HttpStatus.INTERNAL_SERVER_ERROR, "the turn failed"));
        }
    }

    /**
     * The turn as the entity sees it, from the request alone: the entity itself and the others in
     * range, each wall resolved against its cell. A wall whose cell isn't among the cells is dropped,
     * not an error: the view is then without it. The entity's own cell must be there.
     */
    static Choice.Situation situation(TurnRequest request) {
        Map<String, Cell> cells = new LinkedHashMap<>();
        request.cells().forEach(c -> cells.put(c.id(), c));
        Map<String, Entity> entities = new LinkedHashMap<>();
        for (Entity entity : request.entities()) {
            if (entity.cell() != null && !cells.containsKey(entity.cell())) {
                LOG.debug("{} names {}, which isn't among the cells sent; dropped", entity.id(), entity.cell());
                continue;
            }
            entities.put(entity.id(), entity);
        }
        entities.put(request.self().id(), request.self());
        AreaView view = AreaView.of(request.area(), new Area(request.area(), request.since()), entities, cells);
        TurnFrame frame = new TurnFrame(request.area(), request.entity(), request.version(), request.since());
        return new Choice.Situation(frame, request.self(), view);
    }

    private static ResponseEntity<String> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(WorldJson.toJson(Map.of("error", String.valueOf(message))));
    }
}
