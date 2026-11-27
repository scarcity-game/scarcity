package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The faction relations (docs/resources.md, "Factions"): faction name to its {@link Faction}. The
 * standard map is {@code /factions.json}; a game may set its own ({@link Game#factions()}). A
 * faction a map doesn't list is neutral.
 */
public final class Factions {

    /** The side of every player entity, whatever its faction. */
    public static final String PLAYER = "player";

    private static final Map<String, Faction> STANDARD = load();

    private Factions() {
    }

    /** The file's shape: {@code {"factions": {name: {"hostileTo": [...]}}}}. */
    record File(@JsonProperty(required = true) Map<String, Faction> factions) {
    }

    /** The standard relations, in the file's order. */
    public static Map<String, Faction> standard() {
        return STANDARD;
    }

    /** The side {@code entity} is on: {@link #PLAYER} for a player, else its faction; null for none. */
    public static String side(Entity entity) {
        return entity instanceof Player ? PLAYER : entity.faction();
    }

    /**
     * Whether {@code self} would attack {@code other} under {@code factions}: {@code other} is one of
     * its foes, or {@code other}'s side is in the {@code hostileTo} of {@code self}'s faction.
     */
    public static boolean hostile(Map<String, Faction> factions, Entity self, Entity other) {
        if (self.foes().contains(other.id())) {
            return true;
        }
        String side = side(other);
        Faction own = self.faction() == null ? null : factions.get(self.faction());
        return side != null && own != null && own.hostileTo().contains(side);
    }

    /** {@code factions} checked and in order, unmodifiable. */
    static Map<String, Faction> checked(Map<String, Faction> factions) {
        Map<String, Faction> copy = new LinkedHashMap<>();
        Check.required(factions, "factions").forEach((name, faction) ->
                copy.put(Check.text(name, "factions"), Check.required(faction, "factions." + name)));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Faction> load() {
        String path = "/factions.json";
        try (InputStream in = Factions.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return checked(WorldJson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), File.class)
                    .factions());
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }
}
