package com.pessimisticit.scarcity.resourcemodel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ResourceKind;

/** The WHERE clause and bound arguments of {@link ResourceStore#list}, for the table alias {@code r}. */
record ListWhere(String sql, List<Object> args) {

    /** Filter keys are bound as parameters anyway; this keeps them sane and keeps logs readable. */
    static final Pattern FILTER_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    static ListWhere of(ResourceKind type, String gameId, Map<String, String> filter) {
        StringBuilder where = new StringBuilder(" WHERE r.type = ?");
        List<Object> args = new ArrayList<>();
        args.add(type.prefix());
        if (gameId != null) {
            where.append(" AND ").append(inGame(type));
            args.add(Ids.require("game", gameId));
        }
        for (Map.Entry<String, String> e : filter.entrySet()) {
            if (!FILTER_KEY.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("invalid filter key: " + e.getKey());
            }
            where.append(" AND r.state ->> ? = ?");
            args.add(e.getKey());
            args.add(e.getValue());
        }
        return new ListWhere(where.toString(), args);
    }

    /**
     * A predicate with one {@code ?} (the game id) for "r is in that game", by nesting parent_id lookups
     * (the {@code (parent_id, type)} index): the game itself, its areas, their entities (no items yet).
     */
    private static String inGame(ResourceKind type) {
        return switch (type) {
            case GAME -> "r.id = ?";
            case AREA -> "r.parent_id = ?";
            case ENTITY -> "r.parent_id IN (SELECT a.id FROM resources a WHERE a.type = 'area' AND a.parent_id = ?)";
        };
    }
}
