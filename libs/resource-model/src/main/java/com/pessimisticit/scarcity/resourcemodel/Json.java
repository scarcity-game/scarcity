package com.pessimisticit.scarcity.resourcemodel;

import com.fasterxml.jackson.databind.json.JsonMapper;

/** The shared mapper for envelopes and batches. */
final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private Json() {
    }
}
