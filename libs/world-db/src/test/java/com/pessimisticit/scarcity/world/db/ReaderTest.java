package com.pessimisticit.scarcity.world.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link AreaLogReader}: pages, the kind filter, first/last. */
class ReaderTest extends DbTest {

    private final Room room;

    ReaderTest() {
        this.room = new Room(resourceStore, tx);
    }

    private static List<Long> seqs(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("entries").forEach(e -> out.add(e.get("seq").asLong()));
        return out;
    }

    @Test
    void pagesAreAscendingAfterTheGivenSeqAndFirstLastCoverTheWholeLog() {
        for (int i = 0; i < 3; i++) {
            advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0);
        }
        JsonNode all = areaLogReader.read(room.area, 0, AreaLogReader.MAX_LIMIT, null);
        long last = all.get("last").asLong();

        JsonNode page = areaLogReader.read(room.area, 2, 3, null);
        assertThat(seqs(page)).containsExactly(3L, 4L, 5L);
        assertThat(page.get("first").asLong()).isEqualTo(1);
        assertThat(page.get("last").asLong()).isEqualTo(last);
        assertThat(seqs(areaLogReader.read(room.area, last, 10, null))).isEmpty();
    }

    @Test
    void theKindFilterKeepsOnlyThatKind() {
        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0);
        advance(room.area, room.waitFor(1.0), new ScriptedRandom(), T0);
        JsonNode intents = areaLogReader.read(room.area, 0, 10, "intent");
        assertThat(intents.get("entries")).hasSize(2).allMatch(e -> e.get("kind").asText().equals("intent"));
    }

    @Test
    void anUnknownAreaIsAnEmptyLog() {
        JsonNode page = areaLogReader.read(Ids.newId(ResourceKind.AREA), 0, 10, null);
        assertThat(page.get("entries")).isEmpty();
        assertThat(page.get("first").isNull()).isTrue();
        assertThat(page.get("last").isNull()).isTrue();
    }

    @Test
    void aLimitOutsideOneToFiveHundredIsTheCallersBug() {
        assertThatThrownBy(() -> areaLogReader.read(room.area, 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> areaLogReader.read(room.area, 0, 501, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
