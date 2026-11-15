package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.db.AreaLock;
import com.pessimisticit.scarcity.world.db.AreaLogReader;
import com.pessimisticit.scarcity.world.db.AreaRepository;
import com.pessimisticit.scarcity.world.db.LogSettings;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import com.pessimisticit.scarcity.world.seed.Seeder;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The world libs as beans, and seeding the test area at startup. */
@Configuration(proxyBeanMethods = false)
public class WorldConfig {

    private static final Logger log = LoggerFactory.getLogger(WorldConfig.class);

    @Bean
    Catalogue catalogue() {
        return Catalogue.standard();
    }

    @Bean
    LogSettings logSettings(@Value("${scarcity.world.log.snapshot-interval-minutes}") long snapshotMinutes,
            @Value("${scarcity.world.log.retention-hours}") long retentionHours) {
        return new LogSettings(Duration.ofMinutes(snapshotMinutes), Duration.ofHours(retentionHours));
    }

    @Bean
    AreaLock areaLock(JdbcTemplate jdbc, LogSettings settings) {
        return new AreaLock(jdbc, settings);
    }

    @Bean
    AreaRepository areaRepository(ResourceStore store, ChangePublisher publisher, JdbcTemplate jdbc,
            LogSettings settings) {
        return new AreaRepository(store, publisher, jdbc, settings);
    }

    @Bean
    AreaLogReader areaLogReader(JdbcTemplate jdbc) {
        return new AreaLogReader(jdbc);
    }

    @Bean
    Seeder seeder(ResourceStore store, TransactionTemplate tx) {
        return new Seeder(store, tx);
    }

    /** Seeds the test area if there is no game yet (libs/world-seed), then writes any missing queue. */
    @Bean
    ApplicationRunner seedTestArea(Seeder seeder, QueueSweep queueSweep) {
        return args -> {
            if (seeder.seed()) {
                log.info("seeded the test area");
            }
            queueSweep.run();
        };
    }
}
