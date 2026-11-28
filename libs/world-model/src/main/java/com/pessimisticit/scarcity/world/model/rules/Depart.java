package com.pessimisticit.scarcity.world.model.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Check;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import com.pessimisticit.scarcity.world.model.Stairs;

/**
 * {@code depart}: removes {@code entity} from the area and reports it as a
 * {@link Departure} by {@code stairs}. Stairs that lead nowhere yet are linked
 * here to the ids their level and paired stairs will be made with, so the
 * link is in this advance's log; the caller makes them. The result always
 * names the area the entity goes to, and whether it is made for this crossing.
 */
public final class Depart implements Effect<Depart.Params> {

    public static final Depart INSTANCE = new Depart();

    private Depart() {
    }

    public record Params(@JsonProperty(required = true) String entity, @JsonProperty(required = true) String stairs) {
        public Params {
            Check.text(entity, "entity");
            Check.text(stairs, "stairs");
        }
    }

    /**
     * The {@code effect} result: where it went.
     *
     * @param made whether the level is made for this crossing (a first visit)
     */
    public record Result(String toStairs, String toArea, boolean made) {
    }

    @Override
    public String name() {
        return "depart";
    }

    @Override
    public Class<Params> params() {
        return Params.class;
    }

    @Override
    public LogTag logTag(Params params, AreaView areaView, String source) {
        return LogTag.MOVEMENT;
    }

    @Override
    public void run(Params params, EffectContext ctx) {
        Entity traveller = ctx.peek(params.entity()).withAction(null);
        Stairs stairs = (Stairs) ctx.peek(params.stairs());
        String toStairs = stairs.to();
        String toArea = stairs.toArea();
        boolean made = toStairs == null;
        if (made) {
            toStairs = Ids.newId(ResourceKind.ENTITY, ctx.random());
            toArea = Ids.newId(ResourceKind.AREA, ctx.random());
            String linkedStairs = toStairs;
            String linkedArea = toArea;
            ctx.update(params.stairs(), s -> ((Stairs) s).withTo(linkedStairs, linkedArea));
        }
        ctx.destroy(params.entity());
        ctx.depart(new Departure(traveller, params.stairs(), toStairs, toArea, made));
        ctx.result(new Result(toStairs, toArea, made));
    }
}
