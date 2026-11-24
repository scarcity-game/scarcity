package com.pessimisticit.scarcity.world.engine;

import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Item;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.random.RandomGenerator;

/**
 * The area's items during one advance (docs/resources.md, "Items and stashes"): each one's state,
 * which entity holds it, and what happened to it. An entity whose items changed is written too, with
 * one version bump for all of it ({@link #holdersChanged}).
 */
final class Holdings {

    private final SortedMap<String, Item> items;
    private final SortedMap<String, Item> view;
    private final Map<String, String> holderOf;
    private final Map<String, List<String>> held;
    private final Map<String, Long> versions;
    private final SortedSet<String> touched;
    private final Set<String> created;
    private final Set<String> destroyed;
    private final Set<String> holdersChanged;

    Holdings(SortedMap<String, List<Item>> snapshot) {
        this.items = new TreeMap<>();
        this.view = Collections.unmodifiableSortedMap(items);
        this.holderOf = new HashMap<>();
        this.held = new HashMap<>();
        this.versions = new HashMap<>();
        this.touched = new TreeSet<>();
        this.created = new LinkedHashSet<>();
        this.destroyed = new HashSet<>();
        this.holdersChanged = new HashSet<>();
        snapshot.forEach((holder, list) -> {
            for (Item item : list) {
                items.put(item.id(), item);
                versions.put(item.id(), item.version());
                hold(holder, item.id());
            }
        });
    }

    SortedMap<String, Item> items() {
        return view;
    }

    List<String> in(String holder) {
        return List.copyOf(held.getOrDefault(holder, List.of()));
    }

    boolean holds(String holder) {
        return !held.getOrDefault(holder, List.of()).isEmpty();
    }

    Item peek(String id) {
        Item state = items.get(id);
        if (state == null) {
            throw new IllegalArgumentException("no item " + id);
        }
        return state;
    }

    void put(String id, Item state) {
        peek(id);
        items.put(id, state);
        touched.add(id);
    }

    /** Adds an item to {@code holder} with a fresh id drawn from {@code rng}, and returns the id. */
    String create(RandomGenerator rng, String holder, Item state) {
        String id = Ids.newId(ResourceKind.ITEM, rng);
        while (items.containsKey(id) || destroyed.contains(id) || versions.containsKey(id)) {
            id = Ids.newId(ResourceKind.ITEM, rng);
        }
        items.put(id, state);
        created.add(id);
        hold(holder, id);
        holdersChanged.add(holder);
        return id;
    }

    /** Removes an item and returns its holder. */
    String destroy(String id) {
        peek(id);
        String holder = holderOf.remove(id);
        items.remove(id);
        held.get(holder).remove(id);
        destroyed.add(id);
        holdersChanged.add(holder);
        return holder;
    }

    private void hold(String holder, String id) {
        holderOf.put(id, holder);
        held.computeIfAbsent(holder, h -> new ArrayList<>()).add(id);
    }

    /** Whether {@code holder}'s items changed in this advance: it is written for it. */
    boolean holderChanged(String holder) {
        return holdersChanged.contains(holder);
    }

    /** The version a log change of the item {@code id} carries; null if none is written. */
    Long writtenVersion(String id) {
        if (destroyed.contains(id)) {
            return null;
        }
        return created.contains(id) ? 1L : versions.get(id) + 1;
    }

    /** Changed by id, created in order, destroyed by id; one made and gone in the advance isn't written. */
    List<Write> writes(Map<String, String> holderAtEnd) {
        List<Write> out = new ArrayList<>();
        touched.stream()
                .filter(id -> !created.contains(id) && !destroyed.contains(id))
                .forEach(id -> out.add(new Write(id, Write.Op.CHANGED, versions.get(id) + 1,
                        items.get(id).withId(id).withVersion(versions.get(id) + 1), holderOf.get(id))));
        created.stream()
                .filter(id -> !destroyed.contains(id))
                .forEach(id -> out.add(new Write(id, Write.Op.CREATED, 1L,
                        items.get(id).withId(id).withVersion(1L), holderOf.get(id))));
        destroyed.stream()
                .filter(id -> !created.contains(id))
                .sorted()
                .forEach(id -> out.add(new Write(id, Write.Op.DESTROYED, null, null, holderAtEnd.get(id))));
        return out;
    }

    /** Each stored item's holder as the snapshot had it, for the writes of destroyed ones. */
    static Map<String, String> holders(SortedMap<String, List<Item>> snapshot) {
        Map<String, String> out = new HashMap<>();
        snapshot.forEach((holder, list) -> list.forEach(item -> out.put(item.id(), holder)));
        return out;
    }
}
