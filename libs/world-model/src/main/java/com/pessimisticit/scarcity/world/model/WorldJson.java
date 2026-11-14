package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.stream.Collectors;

/**
 * The one JSON mapping of the model. Strict on input: scalars are not
 * coerced ({@code "1.0"} is not a number), unknown keys fail except where a
 * record keeps them in {@code extra}, and every error is a
 * {@link WorldJsonException} that names the key. Optional values are left
 * out of the output, never written as {@code null}.
 */
public final class WorldJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,
                    JsonInclude.Include.NON_NULL))
            .build();

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
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read back " + value.getClass().getSimpleName(), e);
        }
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write " + value.getClass().getSimpleName(), e);
        }
    }

    public static <T> T fromJson(JsonNode tree, Class<T> type) {
        try {
            return MAPPER.treeToValue(tree, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalid(type, e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw invalid(type, e);
        }
    }

    private static WorldJsonException invalid(Class<?> type, Exception e) {
        String path = e instanceof JsonMappingException m ? pathOf(m) : "";
        String where = path.isEmpty() ? "" : " at '" + path + "'";
        return new WorldJsonException(type.getSimpleName() + where + ": " + detail(e), path, e);
    }

    private static String pathOf(JsonMappingException e) {
        return e.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : String.valueOf(ref.getIndex()))
                .collect(Collectors.joining("."));
    }

    private static String detail(Exception e) {
        if (e instanceof InvalidTypeIdException t) {
            return unknownKind(t);
        }
        Throwable cause = e.getCause();
        if (cause instanceof IllegalArgumentException) {
            return cause.getMessage();
        }
        return e instanceof JsonProcessingException p ? p.getOriginalMessage() : e.getMessage();
    }

    private static String unknownKind(InvalidTypeIdException e) {
        JsonTypeInfo info = e.getBaseType().getRawClass().getAnnotation(JsonTypeInfo.class);
        String key = info == null ? "type id" : info.property();
        return e.getTypeId() == null
                ? key + ": required"
                : key + ": unknown " + e.getBaseType().getRawClass().getSimpleName() + " '" + e.getTypeId() + "'";
    }
}
