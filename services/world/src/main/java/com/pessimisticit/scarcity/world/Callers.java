package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.model.Controller;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling, as the controller an entity must name to take their intent
 * (docs/actions.md, "Controllers"): a JWT with {@code kind=service} is the
 * service principal its {@code sub} names; any other is the user its
 * {@code sub} names. {@link SecurityConfig} rejects every other {@code kind}
 * before this. Anything but a JWT can't reach an endpoint, and is an error
 * rather than a guess.
 */
final class Callers {

    /** auth's claim telling a service principal's token from a user's (absent or {@code user}). */
    static final String KIND = "kind";
    static final String SERVICE = "service";
    static final String USER = "user";

    private Callers() {
    }

    static Controller of(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            String sub = jwt.getToken().getSubject();
            return SERVICE.equals(jwt.getToken().getClaimAsString(KIND))
                    ? new Controller.Service(sub)
                    : new Controller.User(sub);
        }
        throw new IllegalStateException("unexpected authentication " + auth.getClass().getName());
    }
}
