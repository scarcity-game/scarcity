package com.pessimisticit.scarcity.controller;

import com.pessimisticit.scarcity.controller.Chooser.Pick;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.TurnAnswer;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Wait;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Plans a turn, with no I/O: every choice the entity may take ({@link Chooser#eligible}) plans,
 * one of the feasible ones is drawn by weight, and the result is the intent's body
 * ({@link TurnAnswer}: the action and {@code reported}, every draw made). With nothing feasible,
 * or when the caller says the answer was refused ({@link #fallback}), the entity waits instead.
 * Shared by both modes: the socket's {@link Player} posts the answer, the endpoint returns it.
 */
public final class Planner {

    private static final Logger LOG = LoggerFactory.getLogger(Planner.class);

    private final Chooser chooser;
    private final double fallbackWait;

    public Planner(Chooser chooser, double fallbackWait) {
        this.chooser = chooser;
        this.fallbackWait = fallbackWait;
    }

    /** How a turn is to be answered. */
    public enum Kind {
        /** A drawn choice's plan. */
        CHOSEN,
        /** No choice gave an intent, so the answer is a {@code wait} of the fallback duration. */
        FALLBACK,
        /** No choice gave an intent and the entity can't wait: there is no answer. */
        UNAVAILABLE
    }

    /**
     * What to answer. {@code choice} is the drawn one (null unless {@link Kind#CHOSEN}), {@code answer}
     * the intent's body (null if {@link Kind#UNAVAILABLE}).
     */
    public record Decision(Kind kind, Choice choice, TurnAnswer answer) {

        /** Whether the answer is a {@code wait}. */
        public boolean isWait() {
            return answer != null && Wait.INSTANCE.name().equals(answer.actionSpec().type());
        }
    }

    /** Plans {@code situation}'s turn for its entity. Draws from the seeded source; nothing is remembered. */
    public Decision decide(Choice.Situation situation) {
        Entity self = situation.entity();
        Map<Choice, Choice.Plan> plans = new LinkedHashMap<>();
        for (Choice choice : chooser.eligible(self.actions())) {
            plans.put(choice, choice.plan(situation));
        }
        Optional<Pick> pick = chooser.pick(self.actions(), choice -> plans.get(choice).feasible());
        if (pick.isEmpty()) {
            LOG.debug("{} has no feasible choice among its actions {}", self.id(), self.actions());
            return fallback(self, "no feasible choice", considered(plans, null));
        }
        Choice.Plan plan = plans.get(pick.get().choice());
        return new Decision(Kind.CHOSEN, pick.get().choice(),
                new TurnAnswer(plan.actionSpec(), reported(pick.get(), plans)));
    }

    /**
     * A {@code wait} of {@code controller.fallback-wait}, so the turn passes to the next entity (turns
     * are strictly ordered, so an entity that does nothing stalls the whole area), and the entity
     * chooses afresh when it is up again; {@link Kind#UNAVAILABLE} when it can't wait. {@code considered}
     * (null: none) is what the choices planned, reported with it.
     */
    public Decision fallback(Entity self, String reason, ObjectNode considered) {
        if (!self.actions().contains(Wait.INSTANCE.name())) {
            return new Decision(Kind.UNAVAILABLE, null, null);
        }
        ActionSpec spec = Wait.INSTANCE.spec(new Wait.Params(fallbackWait));
        ObjectNode reported = WorldJson.toObject(new FallbackReported(new Fallback(reason, fallbackWait)));
        if (considered != null) {
            reported.set("considered", considered);
        }
        return new Decision(Kind.FALLBACK, null, new TurnAnswer(spec, reported));
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
}
