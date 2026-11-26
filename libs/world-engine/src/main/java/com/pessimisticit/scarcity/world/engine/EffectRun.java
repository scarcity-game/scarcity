package com.pessimisticit.scarcity.world.engine;

import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.ActionInProgress;
import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Item;
import com.pessimisticit.scarcity.world.model.Stash;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.rules.Departure;
import com.pessimisticit.scarcity.world.model.rules.EffectCall;
import com.pessimisticit.scarcity.world.model.rules.EffectContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    record Cancelled(String entity, ActionInProgress actionInProgress) {
    }

    private final AreaRun areaRun;
    private final RandomGenerator rng;
    private final Work.Origin self;
    private final List<LogChange> changes;
    private final List<LoggedDraw> draws;
    private final List<Cancelled> cancelled;
    private final List<Work> followUps;
    private final List<Departure> departures;
    private final Set<String> actedUpon;
    private final Set<String> created;
    private ObjectNode result;

    /** @param self this effect's time, its own seq (the cause of what it sets off) and its root */
    EffectRun(AreaRun areaRun, RandomGenerator rng, Work.Origin self) {
        this.changes = new ArrayList<>();
        this.draws = new ArrayList<>();
        this.cancelled = new ArrayList<>();
        this.followUps = new ArrayList<>();
        this.departures = new ArrayList<>();
        this.actedUpon = new LinkedHashSet<>();
        this.created = new HashSet<>();
        this.areaRun = areaRun;
        this.rng = rng;
        this.self = self;
    }

    @Override
    public String areaId() {
        return areaRun.areaId();
    }

    @Override
    public Area area() {
        return areaRun.area();
    }

    @Override
    public SortedMap<String, Entity> entities() {
        return areaRun.entities();
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
    public void update(String id, UnaryOperator<Entity> change) {
        Entity next = change.apply(areaRun.peek(id));
        areaRun.put(id, next);
        actedUpon.add(id);
        record(id, next);
    }

    /** Puts {@code next} in the log changes: into the entity's entry if it has one, else as a new one. */
    private void record(String id, Entity next) {
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
    public String create(String parentId, Entity state) {
        String id = areaRun.create(rng, parentId, state);
        created.add(id);
        changes.add(LogChange.created(id, state, parentId));
        return id;
    }

    @Override
    public SortedMap<String, Item> items() {
        return areaRun.items();
    }

    @Override
    public List<String> itemsIn(String parentId) {
        return areaRun.itemsIn(parentId);
    }

    @Override
    public String createItem(String parentId, Item item) {
        String id = areaRun.createItem(rng, parentId, item);
        changes.add(LogChange.itemCreated(id, item, parentId));
        holderChanged(parentId);
        return id;
    }

    @Override
    public void updateItem(String id, Item state) {
        areaRun.putItem(id, state);
        refreshStash(areaRun.holderOf(id));
        for (int i = 0; i < changes.size(); i++) {
            LogChange c = changes.get(i);
            if (c.id().equals(id) && c.op() != LogChange.Op.DESTROYED) {
                changes.set(i, c.withState(state));
                return;
            }
        }
        changes.add(LogChange.itemChanged(id, state));
    }

    @Override
    public void destroyItem(String id) {
        String holder = areaRun.destroyItem(id);
        changes.add(LogChange.itemDestroyed(id));
        if (areaRun.peek(holder) instanceof Stash && !areaRun.holdsItems(holder)) {
            destroy(holder);
        } else {
            holderChanged(holder);
        }
    }

    /** The holder is written for its items' sake, so the entry says so, once (docs/log.md). */
    private void holderChanged(String holder) {
        refreshStash(holder);
        if (changes.stream().noneMatch(c -> c.id().equals(holder))) {
            changes.add(LogChange.changed(holder, areaRun.peek(holder)));
        }
    }

    /**
     * A stash's mass is its items' total, and its radius follows it (docs/resources.md); a change is
     * logged like any other.
     */
    private void refreshStash(String holder) {
        if (!(areaRun.peek(holder) instanceof Stash stash) || !areaRun.holdsItems(holder)) {
            return;
        }
        double grams = areaRun.itemsIn(holder).stream().mapToDouble(i -> areaRun.items().get(i).mass()).sum();
        double radius = Stash.radiusFor(grams);
        if (stash.mass() == null || stash.mass() != grams || stash.radius() == null || stash.radius() != radius) {
            Entity next = stash.withMass(grams).withRadius(radius);
            areaRun.put(holder, next);
            record(holder, next);
        }
    }

    @Override
    public void depart(Departure departure) {
        departures.add(departure);
    }

    @Override
    public void destroy(String id) {
        Entity last = areaRun.destroy(id);
        if (last.actionInProgress() != null) {
            cancelled.add(new Cancelled(id, last.actionInProgress()));
        }
        changes.add(LogChange.destroyed(id));
    }

    @Override
    public void startAction(String id, ActionSpec actionInProgress) {
        followUps.add(new Work.StartAction(id, actionInProgress, self));
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

    /** The existing resources this effect changed, in order: the ones to interrupt (the engine leaves out its source). */
    List<String> actedUpon() {
        return actedUpon.stream().filter(id -> !created.contains(id)).toList();
    }

    List<Departure> departures() {
        return departures;
    }

    List<Work> followUps() {
        return followUps;
    }
}
