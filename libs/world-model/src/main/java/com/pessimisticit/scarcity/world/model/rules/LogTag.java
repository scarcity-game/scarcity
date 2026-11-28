package com.pessimisticit.scarcity.world.model.rules;

import com.pessimisticit.scarcity.world.model.Check;
import java.util.Arrays;

/**
 * How a log entry reads among the player's messages (docs/log.md, "Messages"): its {@code type},
 * what it is about, and its {@code level}, how much it matters, from {@link #MIN_LEVEL} to
 * {@link #MAX_LEVEL}. Every entry of the area log is in the category {@link #CATEGORY}. Each action
 * and effect type declares its own; the engine copies it into the entries it writes.
 */
public record LogTag(String type, int level) {

    /** The category of every area log entry. */
    public static final String CATEGORY = "game";

    public static final int MIN_LEVEL = 0;
    public static final int MAX_LEVEL = 11;

    /** Anything that happens with no tag of its own, and an old entry stored without one. */
    public static final LogTag EVENT = new LogTag("event", 2);
    public static final LogTag MOVEMENT = new LogTag("movement", 3);
    public static final LogTag WAIT = new LogTag("wait", 1);
    /** The type of a blow or a shot: strike, melee-attack, throw, line-attack. */
    public static final String ATTACK = "attack";
    /** The level of an attack or damage when no entity a user plays takes part in it. */
    public static final int ATTACK_LEVEL = 5;
    /** The level of an attack or damage when an entity a user plays is the attacker or the target. */
    public static final int PLAYED_ATTACK_LEVEL = 7;
    /** An {@code intent} entry, whoever sent it. */
    public static final LogTag INTENT = new LogTag("intent", 4);
    /** Bookkeeping: {@code queue} and {@code catch-up}. */
    public static final LogTag TURN = new LogTag("turn", 0);
    public static final LogTag SNAPSHOT = new LogTag("snapshot", 0);
    /** The level of an {@code action-progress} entry, whatever the action. */
    public static final int PROGRESS_LEVEL = 1;

    public LogTag {
        Check.text(type, "type");
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level: " + level + " is not in [" + MIN_LEVEL + ", " + MAX_LEVEL + "]");
        }
    }

    /**
     * {@code type} at {@code played} when any of {@code ids} is an entity a user plays
     * ({@link AreaView#playedByUser}), else at {@code level}. A null id counts as no one.
     */
    public static LogTag involving(String type, int level, int played, AreaView areaView, String... ids) {
        boolean user = Arrays.stream(ids).anyMatch(id -> id != null && areaView.playedByUser(id));
        return new LogTag(type, user ? played : level);
    }

    /** The tag of an {@code action-progress} entry of an action with this tag. */
    public LogTag progress() {
        return new LogTag(type, PROGRESS_LEVEL);
    }
}
