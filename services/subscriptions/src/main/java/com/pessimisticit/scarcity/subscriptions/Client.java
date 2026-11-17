package com.pessimisticit.scarcity.subscriptions;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * One connected socket. All writes go through a {@link ConcurrentWebSocketSessionDecorator}
 * (one writer at a time, bounded buffer, send time limit; a client that overflows is closed).
 */
final class Client {

    private static final Logger log = LoggerFactory.getLogger(Client.class);

    static final int CLOSE_BAD_FRAME = 4400;
    static final int CLOSE_UNAUTHORIZED = 4401;

    private final WebSocketSession out;
    /** Areas this socket is subscribed to. */
    final Set<String> areas;
    /** The areas of {@link #areas} the turn feed subscribed it to. */
    final Set<String> fedAreas;
    private volatile boolean closed;
    volatile boolean authed;
    volatile String user;
    volatile String username;
    /** A service principal's token ({@code kind=service}): {@link #user} is the principal. */
    volatile boolean service;
    /** The raw JWT, passed to world on subscribe. */
    volatile String token;
    volatile ScheduledFuture<?> authTimer;
    volatile ScheduledFuture<?> expiryTimer;

    Client(WebSocketSession session, SubscriptionsProperties subscriptionsProperties) {
        this.areas = ConcurrentHashMap.newKeySet();
        this.fedAreas = ConcurrentHashMap.newKeySet();
        this.out = new ConcurrentWebSocketSessionDecorator(session,
                (int) subscriptionsProperties.getSendTimeLimit().toMillis(), subscriptionsProperties.getSendBufferBytes());
    }

    String id() {
        return out.getId();
    }

    boolean isOpen() {
        return !closed && out.isOpen();
    }

    void markClosed() {
        closed = true;
    }

    void send(TextMessage message) {
        if (closed) {
            return;
        }
        try {
            out.sendMessage(message);
        } catch (IOException | RuntimeException e) {
            // Includes SessionLimitExceededException (slow client): the decorator has closed the socket.
            log.debug("send to {} failed: {}", id(), e.toString());
        }
    }

    void ping() {
        if (closed) {
            return;
        }
        try {
            out.sendMessage(new PingMessage());
        } catch (IOException | RuntimeException e) {
            log.debug("ping to {} failed: {}", id(), e.toString());
        }
    }

    void close(int code, String reason) {
        try {
            out.close(new CloseStatus(code, reason));
        } catch (IOException | RuntimeException e) {
            log.debug("close of {} failed: {}", id(), e.toString());
        }
    }

    void cancelTimers() {
        ScheduledFuture<?> a = authTimer;
        if (a != null) {
            a.cancel(false);
        }
        ScheduledFuture<?> x = expiryTimer;
        if (x != null) {
            x.cancel(false);
        }
    }
}
