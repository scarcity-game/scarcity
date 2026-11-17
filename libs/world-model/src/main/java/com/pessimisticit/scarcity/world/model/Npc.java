package com.pessimisticit.scarcity.world.model;

/** A scripted character played by a service, in the same turn queue as players. */
@StoredResource
public final class Npc extends Entity {

    /** For Jackson. */
    private Npc() {
    }

    public Npc(String prototype, String name) {
        super(prototype, name);
        validate();
    }

    @Override
    public EntityKind entityKind() {
        return EntityKind.NPC;
    }

    /** An npc keeps its area running, like a player. */
    @Override
    public boolean occupies() {
        return true;
    }
}
