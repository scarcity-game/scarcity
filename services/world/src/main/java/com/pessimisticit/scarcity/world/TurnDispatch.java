package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.resourcemodel.ResourceNotFoundException;
import com.pessimisticit.scarcity.world.db.AreaRepository;
import com.pessimisticit.scarcity.world.db.TurnOutbox;
import com.pessimisticit.scarcity.world.engine.AreaSnapshot;
import com.pessimisticit.scarcity.world.engine.IntentRejected;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Turn;
import com.pessimisticit.scarcity.world.model.TurnAnswer;
import com.pessimisticit.scarcity.world.model.TurnRequest;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One claimed turn (docs/actions.md, "Intelligence services"): read the committed area, ask the
 * service, apply its answer as the principal's intent. Holds no area lock while the service thinks;
 * the intent's advance takes it, and {@code ifVersion} turns a lost race into a stale turn.
 */
class TurnDispatch {

    /** How a dispatch ended. */
    enum Outcome {
        /** The intent was applied; the advance wrote the area's next turn. */
        APPLIED,
        /** A newer turn exists, or the entity is gone: the turn is dropped. */
        STALE,
        /** Applying the answer was refused as invalid (422): retried, the service draws afresh. */
        INVALID;

        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final AreaRepository areaRepository;
    private final IntelligenceProperties intelligenceProperties;
    private final IntelligenceClient intelligenceClient;
    private final Intents intents;
    private final TransactionTemplate readOnly;

    TurnDispatch(AreaRepository areaRepository, IntelligenceProperties intelligenceProperties,
            IntelligenceClient intelligenceClient, Intents intents, TransactionTemplate readOnly) {
        this.areaRepository = areaRepository;
        this.intelligenceProperties = intelligenceProperties;
        this.intelligenceClient = intelligenceClient;
        this.intents = intents;
        this.readOnly = readOnly;
    }

    /**
     * @throws IntelligenceClient.Failed if the service gave no usable answer
     * @throws RuntimeException if world itself failed (the database): retried like a service failure
     */
    Outcome run(TurnOutbox.Due due) {
        Optional<TurnRequest> request = readOnly.execute(s -> request(due));
        if (request == null || request.isEmpty()) {
            return Outcome.STALE;
        }
        TurnAnswer answer = intelligenceClient.turn(due.principal(), request.get());
        IntentRequest intent = new IntentRequest(due.entity(), answer.actionSpec(), request.get().version(),
                answer.reported());
        try {
            intents.post(new Controller.Service(due.principal()), intent);
            return Outcome.APPLIED;
        } catch (IntentRejected e) {
            return e.reason() == IntentRejected.Reason.INVALID ? Outcome.INVALID : Outcome.STALE;
        }
    }

    /** Empty when the area's turn isn't this one any more. */
    private Optional<TurnRequest> request(TurnOutbox.Due due) {
        AreaSnapshot snapshot;
        try {
            snapshot = areaRepository.load(due.areaId());
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
        Turn up = snapshot.area().activeEntity();
        if (up == null || !up.entity().equals(due.entity()) || up.since() != due.since()) {
            return Optional.empty();
        }
        double range = intelligenceProperties.services().get(due.principal()).range();
        return TurnContexts.of(snapshot, due.entity(), due.since(), range);
    }
}
