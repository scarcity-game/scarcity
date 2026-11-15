package com.pessimisticit.scarcity.auth.principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audit lines for service-token exchanges, on the same logger and in the same shape as the
 * account events ({@code user.AuditLog}). Never the key, not even hashed; a failure is one
 * line whatever the cause (missing or unknown key).
 */
final class ServiceAudit {

    private static final Logger log = LoggerFactory.getLogger("com.pessimisticit.scarcity.auth.audit");

    private ServiceAudit() {
    }

    static void issued(String principal) {
        log.atInfo()
                .addKeyValue("event.action", "service_token")
                .addKeyValue("event.outcome", "success")
                .addKeyValue("service.principal", principal)
                .log("service-token issued for principal {}", principal);
    }

    static void refused() {
        log.atInfo()
                .addKeyValue("event.action", "service_token")
                .addKeyValue("event.outcome", "failure")
                .addKeyValue("event.reason", "invalid_key")
                .log("service-token refused: invalid_key");
    }
}
