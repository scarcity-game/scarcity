package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.pessimisticit.scarcity.resourcemodel.ChangeBatch.Change.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

/**
 * The NOTIFY payload (docs/resources.md), byte for byte ({@link Golden}): an update of the area with
 * children added and removed, an update of an entity with set and unset, created, deleted, a stub, an
 * empty update, a part of a split batch and a reload. See {@link Golden} for a changed line.
 */
class ChangeBatchGoldenTest {

    @Test
    void batchesAreReadAndWrittenAsTheirGoldens() throws JacksonException {
        List<String> written = new ArrayList<>();
        for (String line : Golden.lines("change-batches.jsonl")) {
            written.add(Json.MAPPER.writeValueAsString(Json.MAPPER.readValue(line, ChangeBatch.class)));
        }

        Golden.assertLines("change-batches.jsonl", written);
    }

    @Test
    void theFirstGoldenHoldsEveryKindOfChange() throws JacksonException {
        ChangeBatch batch = Json.MAPPER.readValue(Golden.lines("change-batches.jsonl").getFirst(), ChangeBatch.class);

        assertThat(batch.changes()).extracting(ChangeBatch.Change::kind)
                .containsExactly(Kind.UPDATE, Kind.UPDATE, Kind.CREATED, Kind.DELETED, Kind.STUB, Kind.UPDATE);
        assertThat(batch.changes().getFirst().childrenAdded()).hasSize(1);
        assertThat(batch.changes().getFirst().childrenRemoved()).hasSize(1);
        assertThat(batch.changes().get(1).unset()).containsExactly("action");
        assertThat(batch.fromLogSeq()).isEqualTo(8809L);
        assertThat(batch.logSeq()).isEqualTo(8813L);
    }
}
