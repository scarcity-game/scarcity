package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.model.Controller;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling, as the controller an entity must name to take their intent
 * (docs/actions.md, "Controllers"): a JWT is the user its {@code sub} names;
 * a valid API key is the service principal it names. Anything else can't
 * reach an endpoint, and is an error rather than a guess.
 */
final class Callers {

    private Callers() {
    }

    static Controller of(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            return new Controller.User(jwt.getToken().getSubject());
        }
        if (auth instanceof ApiKeyAuthentication key) {
            return new Controller.Service(key.getName());
        }
        throw new IllegalStateException("unexpected authentication " + auth.getClass().getName());
    }
}
