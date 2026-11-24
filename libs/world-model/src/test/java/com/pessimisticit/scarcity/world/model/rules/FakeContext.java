package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.ActionSpec;
import com.pessimisticit.scarcity.world.model.Area;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Item;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

/** An EffectContext that applies changes to a map and records everything else. */
final class FakeContext implements EffectContext {

    static final String AREA = "area:00000000-0000-4000-8000-000000000002";

    record Started(String id, ActionSpec actionSpec) {
    }

    record Drawn(String forWhat, Draw draw) {
    }

    final SortedMap<String, Entity> entities;
    final List<EffectCall<?>> triggered;
    final List<Started> started;
    final List<Drawn> draws;
    final List<String> destroyed;
    final List<String> created;
    final List<Departure> departures = new ArrayList<>();
    final SortedMap<String, Item> items = new TreeMap<>();
    final Map<String, List<String>> held = new HashMap<>();
    Record result;
    private final Area area;
    private final RandomGenerator random;

    FakeContext(Area area, Map<String, Entity> entities, RandomGenerator random) {
        this.entities = new TreeMap<>();
        this.triggered = new ArrayList<>();
        this.started = new ArrayList<>();
        this.draws = new ArrayList<>();
        this.destroyed = new ArrayList<>();
        this.created = new ArrayList<>();
        this.area = area;
        this.entities.putAll(entities);
        this.random = random;
    }

    @Override
    public String areaId() {
        return AREA;
    }

    @Override
    public Area area() {
        return area;
    }

    @Override
    public SortedMap<String, Entity> entities() {
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
    public void update(String id, UnaryOperator<Entity> change) {
        entities.put(id, change.apply(peek(id)));
    }

    @Override
    public String create(String parentId, Entity state) {
        String id = "entity:created-" + (created.size() + 1);
        entities.put(id, state);
        created.add(id);
        return id;
    }

    @Override
    public SortedMap<String, Item> items() {
        return items;
    }

    @Override
    public List<String> itemsIn(String parentId) {
        return List.copyOf(held.getOrDefault(parentId, List.of()));
    }

    @Override
    public String createItem(String parentId, Item item) {
        peek(parentId);
        String id = "item:created-" + (items.size() + 1);
        items.put(id, item);
        held.computeIfAbsent(parentId, k -> new ArrayList<>()).add(id);
        return id;
    }

    @Override
    public void updateItem(String id, Item state) {
        peekItem(id);
        items.put(id, state);
    }

    @Override
    public void destroyItem(String id) {
        peekItem(id);
        items.remove(id);
        held.values().forEach(list -> list.remove(id));
    }

    @Override
    public void depart(Departure departure) {
        departures.add(departure);
    }

    @Override
    public void destroy(String id) {
        peek(id);
        entities.remove(id);
        destroyed.add(id);
    }

    @Override
    public void startAction(String id, ActionSpec actionSpec) {
        started.add(new Started(id, actionSpec));
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
