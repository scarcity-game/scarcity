package com.pessimisticit.scarcity.orbital;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Every log event (root logger, DEBUG and up) while open, with its stack trace as text. */
final class CapturedLogs implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Level previous = root.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    CapturedLogs() {
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
    }

    List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** Each event as "LEVEL logger message" plus its stack trace. */
    List<String> lines() {
        return events().stream().map(e -> e.getLevel() + " " + e.getLoggerName() + " " + e.getFormattedMessage()
                + (e.getThrowableProxy() == null ? "" : "\n" + ThrowableProxyUtil.asString(e.getThrowableProxy())))
                .toList();
    }

    /** The messages at {@code level} logged by the calling thread (a cached Spring context's loop may log too). */
    List<String> at(Level level) {
        String thread = Thread.currentThread().getName();
        return events().stream().filter(e -> e.getLevel() == level && e.getThreadName().equals(thread))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(previous);
    }
}
