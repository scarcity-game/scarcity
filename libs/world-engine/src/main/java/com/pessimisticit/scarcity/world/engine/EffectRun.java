package com.pessimisticit.scarcity.world.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.EntityState;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.EffectCall;
import com.pessimisticit.scarcity.world.model.rules.EffectContext;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

/**
 * What one effect does, collected for its log entry: the changes (each
 * with the state right after this effect), draws, result, the actions it
 * cancelled and the steps it set off.
 */
final class EffectRun implements EffectContext {

    /** An action that was in progress on an entity this effect destroyed. */
    record Cancelled(String entity, ActionInProgress action) {
    }

    private final World world;
    private final RandomGenerator rng;
    private final Work.Origin self;
    private final List<LogChange> changes = new ArrayList<>();
    private final List<LoggedDraw> draws = new ArrayList<>();
    private final List<Cancelled> cancelled = new ArrayList<>();
    private final List<Work> followUps = new ArrayList<>();
    private ObjectNode result;

    /** @param self this effect's time, its own seq (the cause of what it sets off) and its root */
    EffectRun(World world, RandomGenerator rng, Work.Origin self) {
        this.world = world;
        this.rng = rng;
        this.self = self;
    }

    @Override
    public String areaId() {
        return world.areaId();
    }

    @Override
    public AreaState area() {
        return world.area();
    }

    @Override
    public SortedMap<String, EntityState> entities() {
        return world.entities();
    }

    @Override
    public double time() {
        return self.time();
    }

    @Override
    public RandomGenerator random() {
        return rng;
    }

    @Override
    public void update(String id, UnaryOperator<EntityState> change) {
        EntityState next = change.apply(world.peek(id));
        world.put(id, next);
        for (int i = 0; i < changes.size(); i++) {
            LogChange c = changes.get(i);
            if (c.id().equals(id) && c.op() != LogChange.Op.DESTROYED) {
                changes.set(i, c.withState(next));
                return;
            }
        }
        changes.add(LogChange.changed(id, next));
    }

    @Override
    public String create(String parentId, EntityState state) {
        String id = world.create(rng, parentId, state);
        changes.add(LogChange.created(id, state, parentId));
        return id;
    }

    @Override
    public void destroy(String id) {
        EntityState last = world.destroy(id);
        if (last.action() != null) {
            cancelled.add(new Cancelled(id, last.action()));
        }
        changes.add(LogChange.destroyed(id));
    }

    @Override
    public void startAction(String id, ActionSpec action) {
        followUps.add(new Work.StartAction(id, action, self));
    }

    @Override
    public void trigger(EffectCall<?> call) {
        followUps.add(new Work.RunEffect(call, self));
    }

    @Override
    public void result(Record r) {
        this.result = r == null ? null : WorldJson.toObject(r);
    }

    @Override
    public <D extends Draw> D draw(String forWhat, D draw) {
        draws.add(new LoggedDraw(forWhat, draw));
        return draw;
    }

    ObjectNode loggedResult() {
        return result;
    }

    List<LogChange> changes() {
        return changes;
    }

    List<LoggedDraw> draws() {
        return draws;
    }

    List<Cancelled> cancelled() {
        return cancelled;
    }

    List<Work> followUps() {
        return followUps;
    }
}
