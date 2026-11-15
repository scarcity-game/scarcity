package com.pessimisticit.scarcity.world;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A request authenticated by a valid {@code X-Api-Key}: the service principal it names. */
final class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String principal;

    ApiKeyAuthentication(String principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal;
    }
}
