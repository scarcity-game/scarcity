package com.pessimisticit.scarcity.world;

import com.pessimisticit.scarcity.world.model.Controller;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling, as the controller an entity must name to take their intent
 * (docs/actions.md, "Controllers"): a JWT is the user its {@code sub} names;
 * any other authentication is the service principal it names (the API keys of
 * #113).
 */
final class Callers {

    private Callers() {
    }

    static Controller of(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            return new Controller.User(jwt.getToken().getSubject());
        }
        return new Controller.Service(auth.getName());
    }
}
