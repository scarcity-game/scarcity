package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * A resource: {@code game ⊃ area ⊃ entity ⊃ item} (docs/resources.md). The
 * JSON is flat: the base keys here, then the class's own. {@code resourceKind}
 * (and, for an entity, {@code entityKind} and {@code prototype}) pick the Java
 * class on read ({@link ResourceDeserializer}); nothing else looks at them.
 *
 * <p>A resource is immutable: {@code with…} returns a copy. Fields are
 * checked by {@link #validate()}, which the constructors and the reader call.
 * {@code id} and {@code version} are null on a resource that isn't stored
 * yet (a prototype, an entity an advance creates); {@code children} is null
 * when the list is unknown or unchanged, and the store fills it on read.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = ResourceDeserializer.class)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({"resourceKind", "entityKind", "prototype", "id", "version", "children"})
public abstract class Resource implements Cloneable {

    private String id;
    private Long version;
    private List<String> children;

    /** For Jackson; the reader validates. */
    protected Resource() {
    }

    @JsonProperty("resourceKind")
    public abstract ResourceKind resourceKind();

    /** The id; null if the resource isn't stored yet. */
    public String id() {
        return id;
    }

    /** The version; throws if the resource isn't stored yet. */
    public long version() {
        if (version == null) {
            throw new IllegalStateException("version: not stored yet");
        }
        return version;
    }

    /** The ids of the resources inside; null if the list is unknown or unchanged. */
    public List<String> children() {
        return children;
    }

    public final boolean stored() {
        return id != null && version != null;
    }

    /** A copy with this id (checked against the kind's prefix); the caller's type is the resource's own. */
    public final <T extends Resource> T withId(String newId) {
        Resource copy = copy();
        copy.id = Ids.require(resourceKind().prefix(), newId);
        return self(copy);
    }

    public final <T extends Resource> T withVersion(long newVersion) {
        Resource copy = copy();
        copy.version = newVersion;
        copy.validate();
        return self(copy);
    }

    /** A copy with these children; null makes them unknown or unchanged. */
    public final <T extends Resource> T withChildren(List<String> newChildren) {
        Resource copy = copy();
        copy.children = newChildren == null ? null : List.copyOf(newChildren);
        return self(copy);
    }

    /**
     * Throws {@link IllegalArgumentException} (with the key first in the
     * message) if a field is wrong; subclasses add their own checks.
     */
    protected void validate() {
        if (id != null) {
            Ids.require(resourceKind().prefix(), id);
        }
        if (version != null && version < 1) {
            throw new IllegalArgumentException("version: must be >= 1, got " + version);
        }
        if (children != null) {
            children = List.copyOf(children);
            children.forEach(c -> Check.text(c, "children"));
        }
    }

    /** Throws if the resource lacks what the store needs: an id and a version. */
    public Resource requireStored() {
        Check.required(id, "id");
        Check.required(version, "version");
        return this;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Resource> T self(Resource copy) {
        return (T) copy;
    }

    /** A shallow copy; fields are immutable values, so it is safe. Callers set fields on it, then {@link #validate()}. */
    @SuppressWarnings("unchecked")
    protected final <T extends Resource> T copy() {
        try {
            return (T) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public final boolean equals(Object other) {
        return this == other || other instanceof Resource r
                && getClass() == r.getClass() && WorldJson.toJson(this).equals(WorldJson.toJson(r));
    }

    @Override
    public final int hashCode() {
        return WorldJson.toJson(this).hashCode();
    }

    @Override
    public final String toString() {
        return WorldJson.toJson(this);
    }
}
