package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The checksum of docs/resources.md, "Versions", against {@code golden/version-checksums.json}, which
 * the web client's test reads too, so both sides agree bit for bit.
 */
class VersionChecksumTest {

    @Test
    void everyVectorGivesItsChecksum() throws IOException {
        JsonNode cases = Json.MAPPER.readTree(Files.readString(Path.of("src/test/resources/golden/version-checksums.json")));
        assertThat(cases.size()).isGreaterThan(4);
        for (JsonNode c : cases) {
            Map<String, Long> versions = new LinkedHashMap<>();
            c.get("versions").properties().forEach(e -> versions.put(e.getKey(), e.getValue().asLong()));
            assertThat(VersionChecksum.hex(VersionChecksum.of(versions))).as(c.get("name").asString())
                    .isEqualTo(c.get("checksum").asString());
        }
    }

    @Test
    void aTermCanBeTakenBackOut() {
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put("entity:00000000-0000-4000-8000-0000ffffffff", 9L);
        versions.put("area:00000000-0000-4000-8000-000000000002", 4L);
        int sum = VersionChecksum.of(versions);

        int moved = sum - VersionChecksum.term("area:00000000-0000-4000-8000-000000000002", 4)
                + VersionChecksum.term("area:00000000-0000-4000-8000-000000000002", 5);
        versions.put("area:00000000-0000-4000-8000-000000000002", 5L);

        assertThat(moved).isEqualTo(VersionChecksum.of(versions));
    }

    @Test
    void anIdThatIsTooShortIsRefused() {
        assertThatThrownBy(() -> VersionChecksum.term("x", 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
