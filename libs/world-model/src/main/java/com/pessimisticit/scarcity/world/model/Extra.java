package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The keys of a state the model doesn't know (content adds them). They are
 * kept as JSON, in input order, so a read followed by a write keeps them.
 */
final class Extra {

    private Extra() {
    }

    /** An unmodifiable copy (values deep-copied); rejects keys the record itself owns. */
    static Map<String, JsonNode> copy(Map<String, JsonNode> extra, Set<String> known) {
        if (extra == null || extra.isEmpty()) {
            return Map.of();
        }
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        extra.forEach((key, value) -> {
            if (known.contains(key)) {
                throw new IllegalArgumentException(key + ": is a known key, not an extra one");
            }
            copy.put(key, Check.required(value, key).deepCopy());
        });
        return Collections.unmodifiableMap(copy);
    }
}
