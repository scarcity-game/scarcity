package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.AreaState;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.EntityState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

/** An EffectContext that applies changes to a map and records everything else. */
final class FakeContext implements EffectContext {

    static final String AREA = "area:00000000-0000-4000-8000-000000000002";

    record Started(String id, ActionSpec action) {
    }

    record Drawn(String forWhat, Draw draw) {
    }

    final SortedMap<String, EntityState> entities = new TreeMap<>();
    final List<EffectCall<?>> triggered = new ArrayList<>();
    final List<Started> started = new ArrayList<>();
    final List<Drawn> draws = new ArrayList<>();
    final List<String> destroyed = new ArrayList<>();
    final List<String> created = new ArrayList<>();
    Record result;
    private final AreaState area;
    private final RandomGenerator random;

    FakeContext(AreaState area, Map<String, EntityState> entities, RandomGenerator random) {
        this.area = area;
        this.entities.putAll(entities);
        this.random = random;
    }

    @Override
    public String areaId() {
        return AREA;
    }

    @Override
    public AreaState area() {
        return area;
    }

    @Override
    public SortedMap<String, EntityState> entities() {
        return Collections.unmodifiableSortedMap(entities);
    }

    @Override
    public double time() {
        return 10.0;
    }

    @Override
    public RandomGenerator random() {
        return random;
    }

    @Override
    public void update(String id, UnaryOperator<EntityState> change) {
        entities.put(id, change.apply(peek(id)));
    }

    @Override
    public String create(String parentId, EntityState state) {
        String id = "entity:created-" + (created.size() + 1);
        entities.put(id, state);
        created.add(id);
        return id;
    }

    @Override
    public void destroy(String id) {
        peek(id);
        entities.remove(id);
        destroyed.add(id);
    }

    @Override
    public void startAction(String id, ActionSpec action) {
        started.add(new Started(id, action));
    }

    @Override
    public void trigger(EffectCall<?> call) {
        triggered.add(call);
    }

    @Override
    public void result(Record r) {
        result = r;
    }

    @Override
    public <D extends Draw> D draw(String forWhat, D draw) {
        draws.add(new Drawn(forWhat, draw));
        return draw;
    }
}
