package com.pessimisticit.scarcity.auth.user;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INFO audit lines for account events, on a dedicated logger
 * ({@value #LOGGER_NAME}) so they can be filtered or routed on their own.
 *
 * <p>The fields are SLF4J key-value pairs. Boot's ECS JSON formatter nests dotted keys, so
 * they come out as ECS fields: {@code "event":{"action","outcome","reason"}} and
 * {@code "user":{"id"}}. The plain-text pattern doesn't print key-value pairs, so each
 * message also says what happened on its own.
 *
 * <p>Rules: never a password or hash. Never the submitted username on a failure (people
 * type passwords into the username field). Every login failure is the same line, whatever
 * the cause, so the log isn't an account-enumeration oracle.
 */
final class AuditLog {

    static final String LOGGER_NAME = "com.pessimisticit.scarcity.auth.audit";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private AuditLog() {
    }

    static void registered(UUID userId) {
        log.atInfo()
                .addKeyValue("event.action", "register")
                .addKeyValue("event.outcome", "success")
                .addKeyValue("user.id", userId.toString())
                .log("register succeeded for user {}", userId);
    }

    static void registerFailedUsernameTaken() {
        log.atInfo()
                .addKeyValue("event.action", "register")
                .addKeyValue("event.outcome", "failure")
                .addKeyValue("event.reason", "username_taken")
                .log("register failed: username_taken");
    }

    static void loggedIn(UUID userId) {
        log.atInfo()
                .addKeyValue("event.action", "login")
                .addKeyValue("event.outcome", "success")
                .addKeyValue("user.id", userId.toString())
                .log("login succeeded for user {}", userId);
    }

    /** Unknown user, wrong password, malformed username, over-long password: all the same. */
    static void loginFailed() {
        log.atInfo()
                .addKeyValue("event.action", "login")
                .addKeyValue("event.outcome", "failure")
                .addKeyValue("event.reason", "invalid_credentials")
                .log("login failed: invalid_credentials");
    }
}
