package com.pessimisticit.scarcity.resourcemodel;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Minimal app: only the library's autoconfiguration is under test, plus a log head per area for tests to set. */
@SpringBootApplication
public class TestApp {

    /** The log head {@code versions} reports per area; tests put entries. */
    static final Map<String, Long> LOG_SEQS = new ConcurrentHashMap<>();

    @Bean
    LogSeqSource logSeqSource() {
        return area -> LOG_SEQS.containsKey(area) ? OptionalLong.of(LOG_SEQS.get(area)) : OptionalLong.empty();
    }
}
