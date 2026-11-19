package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The NOTIFY payload (docs/resources.md), byte for byte ({@link Golden}): whole
 * resources, a tombstone, a stub, with and without logSeq and traceparent. See {@link Golden} for a
 * changed line.
 */
class ChangeBatchGoldenTest {

    @Test
    void batchesAreReadAndWrittenAsTheirGoldens() throws JsonProcessingException {
        List<String> written = new ArrayList<>();
        for (String line : Golden.lines("change-batches.jsonl")) {
            ChangeBatch batch = Json.MAPPER.readValue(line, ChangeBatch.class);
            written.add(Json.MAPPER.writeValueAsString(batch));
        }

        Golden.assertLines("change-batches.jsonl", written);
    }

    @Test
    void theFirstGoldenHoldsEveryShapeOfChange() throws JsonProcessingException {
        ChangeBatch batch = Json.MAPPER.readValue(Golden.lines("change-batches.jsonl").getFirst(), ChangeBatch.class);

        assertThat(batch.changes()).extracting(ChangeBatch.Change::hasBody).containsExactly(true, true, false, false);
        assertThat(batch.changes()).extracting(ChangeBatch.Change::deleted).containsExactly(false, false, true, false);
        assertThat(batch.changes().get(1).resource()).isPresent();
        assertThat(batch.logSeq()).isEqualTo(8813L);
        // What the publisher writes for a resource is the golden's text, not just the tree passed through.
        for (ChangeBatch.Change c : batch.changes().subList(0, 2)) {
            assertThat(Json.MAPPER.writeValueAsString(ChangeBatch.Change.of(c.resource().orElseThrow())))
                    .isEqualTo(Json.MAPPER.writeValueAsString(c));
        }
    }
}
