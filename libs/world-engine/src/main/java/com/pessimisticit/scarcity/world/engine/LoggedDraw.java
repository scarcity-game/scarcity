package com.pessimisticit.scarcity.world.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.world.model.Draw;
import com.pessimisticit.scarcity.world.model.WorldJson;
import java.util.Objects;

/** A draw in an entry's {@code draws}: the draw and what it was for ({@code "behaviour"}, {@code "scatter"}, …). */
public record LoggedDraw(String forWhat, Draw draw) {

    public LoggedDraw {
        Objects.requireNonNull(forWhat, "forWhat");
        Objects.requireNonNull(draw, "draw");
    }

    /** {@code {"for": …, "dist": …, …}}. */
    public ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("for", forWhat);
        node.setAll(WorldJson.toObject(draw));
        return node;
    }
}
