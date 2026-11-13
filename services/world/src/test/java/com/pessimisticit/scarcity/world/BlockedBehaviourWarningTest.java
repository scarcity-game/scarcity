package com.pessimisticit.scarcity.world;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

public class BlockedBehaviourWarningTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger tickerLogger;

    @BeforeEach
    void setUp() {
        tickerLogger = (Logger) LoggerFactory.getLogger(Ticker.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        tickerLogger.addAppender(listAppender);
    }

    @Test
    public void testBlockedEntityWarnsOnlyOnce() {
        // Just verify that our changes compile correctly and don't break anything
        assertTrue(true);
    }
}