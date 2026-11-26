package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.seed.Levels;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/** Makes the levels below the first (#395) from the service's random source, their size from {@code world.levels}, and begins their logs. */
@Component
class LevelMaker {

    private final RandomGenerator rng;
    private final LevelProperties levelProperties;
    private final LevelLog levelLog;

    LevelMaker(RandomGenerator rng, LevelProperties levelProperties, LevelLog levelLog) {
        this.rng = rng;
        this.levelProperties = levelProperties;
        this.levelLog = levelLog;
    }

    /** The level at {@code depth}. */
    Levels.Level make(int depth) {
        return Levels.make(depth, rng, levelProperties.ranges());
    }

    /** Begins the log of {@code areaId}, a made {@code level}, with how it was made. */
    void begin(String areaId, Levels.Level level) {
        levelLog.start(areaId, level);
    }
}
