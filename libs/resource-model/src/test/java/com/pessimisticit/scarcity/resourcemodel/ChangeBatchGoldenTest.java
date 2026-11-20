package com.pessimisticit.scarcity.resourcemodel;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.core.JacksonException;
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
    void batchesAreReadAndWrittenAsTheirGoldens() throws JacksonException {
        List<String> written = new ArrayList<>();
        for (String line : Golden.lines("change-batches.jsonl")) {
            ChangeBatch read = Json.MAPPER.readValue(line, ChangeBatch.class);
            // As the publisher writes it: a resource through Change.of, a tombstone or stub as it is.
            List<ChangeBatch.Change> changes = read.changes().stream()
                    .map(c -> c.hasBody() ? ChangeBatch.Change.of(c.resource().orElseThrow()) : c).toList();
            written.add(Json.MAPPER.writeValueAsString(
                    new ChangeBatch(read.area(), changes, read.logSeq(), read.traceparent())));
        }

        Golden.assertLines("change-batches.jsonl", written);
    }

    @Test
    void theFirstGoldenHoldsEveryShapeOfChange() throws JacksonException {
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
