package com.pessimisticit.scarcity.resourcemodel;

import java.util.OptionalLong;

/**
 * Where the generic API reads an area's log head for {@code GET {prefix}/{id}/versions}
 * (docs/resources.md). A service with area logs provides one bean; without it the field is left out.
 */
public interface LogSeqSource {

    /** The highest {@code seq} of {@code areaId}'s log; empty when it has none. */
    OptionalLong logSeq(String areaId);
}
