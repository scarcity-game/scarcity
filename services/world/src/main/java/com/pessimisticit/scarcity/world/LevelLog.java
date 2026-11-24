package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.db.AreaLock;
import com.pessimisticit.scarcity.world.db.AreaRepository;
import com.pessimisticit.scarcity.world.engine.LoggedDraw;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.seed.Levels;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * Begins the log of a generated level (#311, docs/log.md, {@code snapshot}): its first snapshot
 * carries {@code generation} (the tessellation and the generator, each {@code {type, params}}) and
 * every draw that made it, each {@code for} its name. Runs in the caller's transaction, after every
 * resource of the level that the snapshot should hold is created.
 */
@Component
class LevelLog {

    private final AreaLock areaLock;
    private final AreaRepository areaRepository;
    private final Clock clock;

    LevelLog(AreaLock areaLock, AreaRepository areaRepository, Clock clock) {
        this.areaLock = areaLock;
        this.areaRepository = areaRepository;
        this.clock = clock;
    }

    void start(String areaId, Levels.Level level) {
        areaRepository.startLog(areaLock.begin(areaId, clock.instant()), areaRepository.load(areaId),
                generation(level), draws(level));
    }

    static ObjectNode generation(Levels.Level level) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("tessellation", level.tessellation());
        generation.put("generator", level.generator());
        return WorldJson.toObject(generation);
    }

    static List<LoggedDraw> draws(Levels.Level level) {
        return level.draws().stream().map(d -> new LoggedDraw(d.name(), d.draw())).toList();
    }
}
