package com.pessimisticit.scarcity.resourcemodel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

import tools.jackson.databind.JsonNode;

/**
 * A small notice on {@link Channels#TURNS}, sent with an area's batch (docs/resources.md, "Turn notices"), so a
 * subscriber that listens to no area can still hear whose turn it is everywhere (the turn feed). Either
 * {@code {"area","controller":{…}}}: the batch's update of the area sets an {@code activeEntity}, and this is
 * its {@code controller}; or {@code {"area","read":true}}: who is up can't be told from the batch (a reload, or
 * the area's update was sent as a stub), so read the area.
 */
@JsonPropertyOrder({"area", "controller", "read", "traceparent"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TurnNotice(String area, JsonNode controller, Boolean read, String traceparent) {

    public TurnNotice {
        Objects.requireNonNull(area, "area");
        read = Boolean.TRUE.equals(read) ? Boolean.TRUE : null;
    }

    /** Whether the area must be read to tell who is up ({@code read} is {@code true}, else absent). */
    public boolean mustRead() {
        return read != null;
    }

    /**
     * The notice for {@code batch} (a whole batch, before any split), or null when the batch doesn't change
     * who is up in its area.
     */
    public static TurnNotice of(ChangeBatch batch) {
        if (batch.reloading()) {
            return new TurnNotice(batch.area(), null, true, batch.traceparent());
        }
        for (ChangeBatch.Change ch : batch.changes()) {
            if (ch == null || !batch.area().equals(ch.id())) {
                continue;
            }
            if (ch.kind() == ChangeBatch.Change.Kind.STUB) {
                return new TurnNotice(batch.area(), null, true, batch.traceparent());
            }
            if (ch.kind() == ChangeBatch.Change.Kind.UPDATE) {
                JsonNode up = ch.set().get("activeEntity");
                if (up != null && up.isObject() && up.hasNonNull("controller")) {
                    return new TurnNotice(batch.area(), up.get("controller"), null, batch.traceparent());
                }
            }
        }
        return null;
    }
}
