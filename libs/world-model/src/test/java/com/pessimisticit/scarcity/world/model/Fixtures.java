package com.pessimisticit.scarcity.world.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Test JSON: files under {@code states/}, and plain trees for comparing (no model mapping). */
final class Fixtures {

    static final ObjectMapper PLAIN = new ObjectMapper();

    private Fixtures() {
    }

    static JsonNode state(String file) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/states/" + file)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + file);
            }
            return PLAIN.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode tree(String json) {
        try {
            return PLAIN.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
