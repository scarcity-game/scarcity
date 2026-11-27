package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.db.AreaLock;
import com.pessimisticit.scarcity.world.db.AreaRepository;
import com.pessimisticit.scarcity.world.engine.LoggedDraw;
import com.pessimisticit.scarcity.world.gen.Recorder;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.seed.Levels;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

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
        start(areaId, generation(level), level.draws());
    }

    /** Begins the log of {@code areaId}, made another way (an arena, #455): {@code generation} and its draws. */
    void start(String areaId, Map<String, Object> generation, List<Recorder.Named> draws) {
        areaRepository.startLog(areaLock.begin(areaId, clock.instant()), areaRepository.load(areaId),
                WorldJson.toObject(generation), draws.stream().map(d -> new LoggedDraw(d.name(), d.draw())).toList());
    }

    static Map<String, Object> generation(Levels.Level level) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("tessellation", level.tessellation());
        generation.put("generator", level.generator());
        generation.put("outline", level.outline());
        return generation;
    }
}
