package com.pessimisticit.scarcity.world.model;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Reads the flat resource JSON into the class its {@code resourceKind},
 * {@code entityKind} and {@code prototype} name, then validates it. The only
 * code that looks at those keys.
 */
final class ResourceDeserializer extends StdDeserializer<Resource> {

    private static final long serialVersionUID = 1L;

    ResourceDeserializer() {
        super(Resource.class);
    }

    @Override
    public Resource deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode node = context.readTree(parser);
        if (!node.isObject()) {
            throw MismatchedInputException.from(parser, Resource.class, "a resource must be a JSON object");
        }
        ResourceKind kind = key(node, "resourceKind", ResourceKind.class, context, parser);
        Class<? extends Resource> type = switch (kind) {
            case GAME -> Game.class;
            case AREA -> Area.class;
            case ENTITY -> Prototypes.classOf(
                    key(node, "entityKind", EntityKind.class, context, parser), text(node, "prototype"));
            case ITEM -> Item.class;
            case CELL -> Cell.class;
        };
        Resource resource = context.readTreeAsValue(node, type);
        try {
            resource.validate();
        } catch (IllegalArgumentException e) {
            throw DatabindException.from(parser, e.getMessage(), e);
        }
        return resource;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static <E extends Enum<E>> E key(JsonNode node, String key, Class<E> type,
            DeserializationContext context, JsonParser parser) {
        String value = text(node, key);
        if (value == null) {
            throw reference(DatabindException.from(parser, key + ": required"), key);
        }
        for (E constant : type.getEnumConstants()) {
            if (WorldJson.toJson(constant).equals("\"" + value + "\"")) {
                return constant;
            }
        }
        throw reference(DatabindException.from(parser, key + ": unknown " + type.getSimpleName() + " '" + value + "'"), key);
    }

    private static DatabindException reference(DatabindException e, String key) {
        e.prependPath(new JacksonException.Reference(Resource.class, key));
        return e;
    }
}
