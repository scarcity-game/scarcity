package com.pessimisticit.scarcity.world.db;

import com.pessimisticit.scarcity.resourcemodel.LogSeqSource;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * An area's log head, {@code area_log_head.last_seq}, for {@code GET …/{id}/versions} (docs/resources.md):
 * 0 for an area whose log is still empty (no head yet), as its first batch's {@code fromLogSeq} will be.
 */
public final class AreaLogSeqs implements LogSeqSource {

    private final JdbcTemplate jdbc;

    public AreaLogSeqs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OptionalLong logSeq(String areaId) {
        List<Long> heads = jdbc.query("SELECT last_seq FROM area_log_head WHERE area_id = ?",
                (rs, i) -> rs.getLong(1), areaId);
        return OptionalLong.of(heads.isEmpty() ? 0 : heads.getFirst());
    }
}
