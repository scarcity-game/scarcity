package com.pessimisticit.scarcity.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Every log event (root logger, DEBUG and up) while open, with its stack trace as text. */
public final class CapturedLogs implements AutoCloseable {

    private final Logger root;
    private final Level previous;
    private final ListAppender<ILoggingEvent> appender;

    public CapturedLogs() {
        this.root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.previous = root.getLevel();
        this.appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** Each event as "LEVEL logger message" plus its stack trace. */
    public List<String> lines() {
        return events().stream().map(e -> e.getLevel() + " " + e.getLoggerName() + " " + e.getFormattedMessage()
                + (e.getThrowableProxy() == null ? "" : "\n" + ThrowableProxyUtil.asString(e.getThrowableProxy())))
                .toList();
    }

    /** The messages at {@code level} logged by the calling thread (a cached Spring context's loop may log too). */
    public List<String> at(Level level) {
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
