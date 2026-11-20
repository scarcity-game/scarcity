package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.stream.Collectors;

/**
 * The one JSON mapping of the model. Strict on input: scalars are not
 * coerced ({@code "1.0"} is not a number), every key must be one the model
 * knows (an unknown key is an error, never dropped), and every error is a
 * {@link WorldJsonException} that names the key. Optional values are left
 * out of the output, never written as {@code null}.
 */
public final class WorldJson {

    /**
     * Every reading rule is set here, not left to the defaults: they are model
     * rules, and Jackson 3 changed some defaults (unknown keys were accepted).
     * Key order is Jackson's: the {@code @JsonPropertyOrder} keys first, then
     * the rest alphabetically (#246).
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                    JsonInclude.Include.NON_NULL))
            .build();

    private static final String MISSING = "Missing required creator property";

    private WorldJson() {
    }

    /**
     * The value as a tree, read back from its JSON text, so numbers have the
     * node types a parser gives ({@code 8812} is an int node, not a long one)
     * and the tree equals the same JSON read from the database.
     */
    public static JsonNode toTree(Object value) {
        try {
            return MAPPER.readTree(toJson(value));
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot read back " + value.getClass().getSimpleName(), e);
        }
    }

    /** JSON text as a tree, read with the model's rules (one value, nothing after it). */
    public static JsonNode readTree(String json) {
        return MAPPER.readTree(json);
    }

    /** {@link #toTree} of a value that maps to a JSON object (a record). */
    public static ObjectNode toObject(Object value) {
        if (toTree(value) instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalArgumentException(value.getClass().getSimpleName() + " is not a JSON object");
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot write " + value.getClass().getSimpleName(), e);
        }
    }

    public static <T> T fromJson(JsonNode tree, Class<T> type) {
        return fromJson(tree, type, type.getSimpleName());
    }

    /**
     * Like {@link #fromJson(JsonNode, Class)}, with {@code what} in place of
     * the record's name in the message: {@code "step params at 'to': x: required"}.
     */
    public static <T> T fromJson(JsonNode tree, Class<T> type, String what) {
        try {
            return checked(type.cast(MAPPER.treeToValue(tree, type)));
        } catch (JacksonException | IllegalArgumentException | ClassCastException e) {
            throw invalid(what, e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return checked(type.cast(MAPPER.readValue(json, type)));
        } catch (JacksonException | IllegalArgumentException | ClassCastException e) {
            throw invalid(type.getSimpleName(), e);
        }
    }

    /** A resource read as its own class skips the deserializer that validates; check it here (twice is harmless). */
    private static <T> T checked(T value) {
        if (value instanceof Resource resource) {
            resource.validate();
        }
        return value;
    }

    private static WorldJsonException invalid(String what, Exception e) {
        String path = e instanceof DatabindException m ? pathOf(m) : "";
        String where = path.isEmpty() ? "" : " at '" + path + "'";
        return new WorldJsonException(what + where + ": " + detail(e, path), path, e);
    }

    private static String pathOf(DatabindException e) {
        return e.getPath().stream()
                .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : String.valueOf(ref.getIndex()))
                .collect(Collectors.joining("."));
    }

    private static String detail(Exception e, String path) {
        if (e instanceof InvalidTypeIdException t) {
            return unknownKind(t);
        }
        if (e instanceof UnrecognizedPropertyException u) {
            return u.getPropertyName() + ": unknown key";
        }
        if (e instanceof MismatchedInputException m && m.getOriginalMessage().startsWith(MISSING)) {
            return path.substring(path.lastIndexOf('.') + 1) + ": required";
        }
        Throwable cause = e.getCause();
        if (cause instanceof IllegalArgumentException) {
            return cause.getMessage();
        }
        return e instanceof JacksonException p ? p.getOriginalMessage() : e.getMessage();
    }

    private static String unknownKind(InvalidTypeIdException e) {
        JsonTypeInfo info = e.getBaseType().getRawClass().getAnnotation(JsonTypeInfo.class);
        String key = info == null ? "type id" : info.property();
        return e.getTypeId() == null
                ? key + ": required"
                : key + ": unknown " + e.getBaseType().getRawClass().getSimpleName() + " '" + e.getTypeId() + "'";
    }
}
