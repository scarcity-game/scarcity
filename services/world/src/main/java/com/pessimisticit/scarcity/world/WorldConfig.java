package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.resourcemodel.ChangePublisher;
import com.pessimisticit.scarcity.resourcemodel.ResourceStore;
import com.pessimisticit.scarcity.world.db.AreaLock;
import com.pessimisticit.scarcity.world.db.AreaLogReader;
import com.pessimisticit.scarcity.world.db.AreaLogSeqs;
import com.pessimisticit.scarcity.world.db.AreaRepository;
import com.pessimisticit.scarcity.world.db.LogSettings;
import com.pessimisticit.scarcity.world.db.TurnOutbox;
import com.pessimisticit.scarcity.world.model.rules.Catalogue;
import com.pessimisticit.scarcity.world.seed.Seeder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

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
            LogSettings settings, TurnOutbox turnOutbox) {
        return new AreaRepository(store, publisher, jdbc, settings, turnOutbox);
    }

    /** The turns owed to intelligence services; a commit that writes one wakes the dispatcher. */
    @Bean
    TurnOutbox turnOutbox(JdbcTemplate jdbc, IntelligenceProperties intelligenceProperties,
            ObjectProvider<TurnDispatcher> turnDispatcher) {
        return new TurnOutbox(jdbc, intelligenceProperties::registered, () -> turnDispatcher.getObject().wake());
    }

    @Bean
    WorldServiceToken worldServiceToken(RestClient.Builder builder, IntelligenceProperties intelligenceProperties,
            Clock clock) {
        return new WorldServiceToken(builder, intelligenceProperties, clock);
    }

    @Bean
    IntelligenceClient intelligenceClient(RestClient.Builder builder, IntelligenceProperties intelligenceProperties,
            WorldServiceToken worldServiceToken) {
        return new IntelligenceClient(builder, intelligenceProperties, worldServiceToken);
    }

    @Bean
    IntelligenceDecider intelligenceDecider(IntelligenceProperties intelligenceProperties,
            IntelligenceClient intelligenceClient, Clock clock) {
        return new IntelligenceDecider(intelligenceProperties, intelligenceClient, clock);
    }

    @Bean
    TurnDispatch turnDispatch(AreaRepository areaRepository, IntelligenceProperties intelligenceProperties,
            IntelligenceClient intelligenceClient, Intents intents, PlatformTransactionManager transactionManager) {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new TurnDispatch(areaRepository, intelligenceProperties, intelligenceClient, intents, readOnly);
    }

    @Bean
    TurnDispatcher turnDispatcher(TurnOutbox turnOutbox, TurnDispatch turnDispatch,
            IntelligenceProperties intelligenceProperties, Tracer tracer, MeterRegistry meters, Clock clock) {
        return new TurnDispatcher(turnOutbox, turnDispatch, intelligenceProperties, tracer, meters, clock);
    }

    /** The log heads for {@code GET …/resources/{id}/versions} (libs/resource-model). */
    @Bean
    AreaLogSeqs areaLogSeqs(JdbcTemplate jdbc) {
        return new AreaLogSeqs(jdbc);
    }

    @Bean
    AreaLogReader areaLogReader(JdbcTemplate jdbc) {
        return new AreaLogReader(jdbc);
    }

    @Bean
    Seeder seeder(ResourceStore store, TransactionTemplate tx, RandomGenerator rng, LevelLog levelLog,
            WorldBuildProperties worldBuildProperties, LevelProperties levelProperties) {
        return new Seeder(store, tx, rng, levelLog::start, worldBuildProperties.sha(), levelProperties.ranges());
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
