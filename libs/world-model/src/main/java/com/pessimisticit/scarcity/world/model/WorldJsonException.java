package com.pessimisticit.scarcity.world.model;

/** JSON that doesn't fit the model. The message names the key; {@link #path()} is where it is. */
public class WorldJsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String path;

    public WorldJsonException(String message, String path, Throwable cause) {
        super(message, cause);
        this.path = path;
    }

    /** The path from the document's root to the bad value ({@code action.params}), empty for the root. */
    public String path() {
        return path;
    }
}
