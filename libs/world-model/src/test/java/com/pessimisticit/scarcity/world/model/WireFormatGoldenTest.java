package com.pessimisticit.scarcity.world.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The resource and draw JSON, byte for byte ({@link Golden}): each golden line,
 * read and written again, is the same text. This is the wire, log and
 * database format; see {@link Golden} for a changed line.
 */
class WireFormatGoldenTest {

    @Test
    void everyResourceKindAndEntityClassIsWrittenAsItsGolden() {
        List<String> lines = Golden.lines("resources.jsonl");
        List<String> written = lines.stream().map(l -> WorldJson.toJson(WorldJson.fromJson(l, Resource.class))).toList();

        Golden.assertLines("resources.jsonl", written);
        assertThat(lines.stream().map(l -> WorldJson.fromJson(l, Resource.class).getClass().getSimpleName()).distinct())
                .containsExactlyInAnyOrder("Game", "Area", "Fixed", "Stairs", "Placeholder", "Player", "Npc", "Mob");
    }

    @Test
    void everyDistributionIsWrittenAsItsGolden() {
        List<String> written = Golden.lines("draws.jsonl").stream()
                .map(l -> WorldJson.toJson(WorldJson.fromJson(l, Draw.class))).toList();

        Golden.assertLines("draws.jsonl", written);
    }

    /** Reading stays strict: each of these is a rejection that names the key. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // a float where an int is expected, even a whole one
        "{\"dist\":\"categorical\",\"weights\":[1.0],\"u\":0.5,\"value\":1.0}                    | value",
        "{\"dist\":\"categorical\",\"weights\":[1.0],\"u\":0.5,\"value\":0.5}                    | value",
        // null for a primitive
        "{\"dist\":\"categorical\",\"weights\":[1.0],\"u\":null,\"value\":0}                     | u",
        // a string is not a number, nor a number a string or a boolean
        "{\"dist\":\"bernoulli\",\"p\":\"0.5\",\"u\":0.1,\"value\":true}                         | p",
        "{\"dist\":\"bernoulli\",\"p\":0.5,\"u\":0.1,\"value\":1}                                | value",
        // an unknown key
        "{\"dist\":\"uniform\",\"min\":0.0,\"max\":1.0,\"value\":0.5,\"seed\":1}                 | seed",
    })
    void strictRejectionsStayRejections(String json, String path) {
        assertThatThrownBy(() -> WorldJson.fromJson(json, Draw.class))
                .isInstanceOfSatisfying(WorldJsonException.class, e -> assertThat(e.path()).isEqualTo(path));
    }

    @Test
    void trailingTokensAndFloatDepthsAreRejected() {
        String area = "{\"resourceKind\":\"area\",\"name\":\"r\",\"time\":0.0}";

        assertThatThrownBy(() -> WorldJson.fromJson(area + " {}", Resource.class)).isInstanceOf(WorldJsonException.class);
        assertThatThrownBy(() -> WorldJson.fromJson(area + "x", Area.class)).isInstanceOf(WorldJsonException.class);
        assertThatThrownBy(() -> WorldJson.fromJson(area.replace("}", ",\"depth\":1.0}"), Resource.class))
                .isInstanceOf(WorldJsonException.class);
    }
}
