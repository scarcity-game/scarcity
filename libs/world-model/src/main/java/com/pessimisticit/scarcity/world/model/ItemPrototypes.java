package com.pessimisticit.scarcity.world.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item prototypes, in {@code prototypes/items/<name>.json}: a namespace apart from the entities'
 * ({@link Prototypes}), so a flying {@code pebble} entity and a {@code pebble} item can share a name.
 */
public final class ItemPrototypes {

    private static final Map<String, Item> LOADED = new ConcurrentHashMap<>();

    private ItemPrototypes() {
    }

    public static Item get(String prototype) {
        Check.text(prototype, "prototype");
        return LOADED.computeIfAbsent(prototype, ItemPrototypes::load);
    }

    private static Item load(String prototype) {
        if (!prototype.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("prototype: not a prototype name: " + prototype);
        }
        try (InputStream in = ItemPrototypes.class.getResourceAsStream("/prototypes/items/" + prototype + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("prototype: unknown item prototype '" + prototype + "'");
            }
            Item item = WorldJson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Item.class);
            if (!prototype.equals(item.prototype())) {
                throw new IllegalStateException("item prototype file " + prototype + ".json says " + item.prototype());
            }
            return item;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
