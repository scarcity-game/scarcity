package com.pessimisticit.scarcity.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Whether a request to {@code /turn} comes from world: a valid token from auth (signature, expiry,
 * issuer) with {@code kind=service} and {@code sub=world} (docs/actions.md, "Intelligence
 * services"). The token is never logged.
 */
final class WorldCaller {

    private static final Logger LOG = LoggerFactory.getLogger(WorldCaller.class);
    private static final String BEARER = "Bearer ";
    /** The principal of world's own service token. */
    static final String SUBJECT = "world";

    /** What to answer the caller. */
    enum Verdict {
        /** It is world. */
        ALLOWED,
        /** No token, or one that isn't valid: 401. */
        UNAUTHENTICATED,
        /** A valid token of someone else: 403. */
        FORBIDDEN,
        /** The token can't be checked now (auth's keys are unreachable): 503, world retries. */
        UNVERIFIABLE
    }

    private final JwtDecoder decoder;

    WorldCaller(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    /** @param authorization the {@code Authorization} header, or null */
    Verdict check(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Verdict.UNAUTHENTICATED;
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(authorization.substring(BEARER.length()).trim());
        } catch (BadJwtException e) {
            LOG.debug("token refused: {}", e.getMessage());
            return Verdict.UNAUTHENTICATED;
        } catch (JwtException e) {
            LOG.warn("cannot verify a token: {}", e.getMessage());
            return Verdict.UNVERIFIABLE;
        }
        boolean world = "service".equals(jwt.getClaimAsString("kind")) && SUBJECT.equals(jwt.getSubject());
        if (!world) {
            LOG.warn("/turn refused for sub {} (kind {})", jwt.getSubject(), jwt.getClaimAsString("kind"));
        }
        return world ? Verdict.ALLOWED : Verdict.FORBIDDEN;
    }
}
