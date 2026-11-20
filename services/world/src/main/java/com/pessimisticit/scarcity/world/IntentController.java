package com.pessimisticit.scarcity.world;

import tools.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.engine.IntentRejected;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.WorldJsonException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/world/intents} (contract: docs/actions.md, "Intents").
 * Counts every intent in {@code world.intents}, tagged by outcome.
 */
@RestController
public class IntentController {

    private final Intents intents;
    private final MeterRegistry meters;

    public IntentController(Intents intents, MeterRegistry meters) {
        this.intents = intents;
        this.meters = meters;
    }

    @PostMapping(path = "/api/world/intents", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Intents.Advanced post(@RequestBody JsonNode body, Authentication auth) {
        IntentRequest request = WorldJson.fromJson(body, IntentRequest.class, "intent");
        try {
            Intents.Advanced advanced = intents.post(Callers.of(auth), request);
            count("ok");
            return advanced;
        } catch (IntentRejected e) {
            count(e.reason().name().toLowerCase(Locale.ROOT));
            throw e;
        } catch (RuntimeException e) {
            count("error");
            throw e;
        }
    }

    @ExceptionHandler(IntentRejected.class)
    ResponseEntity<Map<String, String>> rejected(IntentRejected e) {
        return error(status(e.reason()), e.getMessage());
    }

    @ExceptionHandler({WorldJsonException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> malformed(RuntimeException e) {
        count("malformed");
        return error(HttpStatus.BAD_REQUEST, e instanceof WorldJsonException ? e.getMessage() : "malformed body");
    }

    static HttpStatus status(IntentRejected.Reason reason) {
        return switch (reason) {
            case UNKNOWN_ENTITY -> HttpStatus.NOT_FOUND;
            case NOT_CONTROLLED -> HttpStatus.FORBIDDEN;
            case STALE, BUSY, NOT_ACTIVE -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private void count(String outcome) {
        Counter.builder("world.intents").description("intents posted, by outcome").tag("outcome", outcome)
                .register(meters).increment();
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
